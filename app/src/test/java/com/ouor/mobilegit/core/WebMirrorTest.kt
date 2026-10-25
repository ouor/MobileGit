package com.ouor.mobilegit.core

import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.Base64

/** F-26 WM-01~03, WM-05 (core): `POST /api/v1/mirrors[/preview]`, its rejections and CSRF defence. */
class WebMirrorTest {
    private val upPassword = "upstream-pass"
    private val password = "phone-password"
    private lateinit var upStore: RepoStore
    private lateinit var upSettings: SettingsStore
    private lateinit var upServer: GitServer
    private var upPort = 0

    private lateinit var store: RepoStore
    private lateinit var settings: SettingsStore
    private lateinit var creds: CredentialStore
    private val env = object : SyncEnvironment {
        var free = Long.MAX_VALUE / 4
        override fun freeBytes() = free
        override fun isOnUnmeteredNetwork() = true
        override fun isCharging() = false
    }
    private val enqueued = mutableListOf<Pair<String, SyncTrigger>>()
    private lateinit var log: AccessLogStore
    private lateinit var server: GitServer
    private lateinit var strict: GitServer // httpsOnly, as in the app
    private var port = 0

    @Before fun setUp() {
        JGitEnv.init()
        val upRoot = tempDir("up")
        upStore = RepoStore(upRoot)
        upSettings = SettingsStore(File(upRoot, "s.json"), FakeCipher).apply { setPassword(upPassword); update { it.copy(authEnabled = false) } }
        upServer = GitServer(upStore, Authenticator({ upSettings.current }, AuthRateLimiter()), AccessLogStore(null), { upSettings.current }, { emptyList() }, { null }, { Long.MAX_VALUE / 4 })
        upPort = (upServer.start(0, "127.0.0.1") as ServerStartResult.Started).port
        val work = Commits.initWork(); Commits.commit(work, "README.md", "hi")
        work.push().setRemote("http://127.0.0.1:$upPort/up.git").setCredentialsProvider(UsernamePasswordCredentialsProvider("git", upPassword))
            .setRefSpecs(RefSpec("+refs/heads/*:refs/heads/*")).call()

        val root = tempDir("phone")
        store = RepoStore(root)
        settings = SettingsStore(File(root, "settings.json"), FakeCipher).apply { setPassword(password) }
        creds = CredentialStore(File(root, "creds.json"), FakeCipher)
        val bulk = BulkJobStore(File(root, "bulk.json"))
        val engine = MirrorEngine(timeoutSeconds = 3)
        val coordinator = SyncCoordinator(store, engine, creds, { settings.current }, env, {}, bulk)
        val scheduler = object : SyncScheduler {
            override fun enqueueUpdate(name: String, trigger: SyncTrigger) { enqueued += name to trigger }
            override fun enqueueCheckNow(name: String) {}
            override fun cancel(name: String) {}
        }
        val mc = MirrorController(store, engine, creds, coordinator, scheduler, { settings.current }, env, bulk)
        log = AccessLogStore(null)
        fun make(httpsOnly: Boolean) = GitServer(store, Authenticator({ settings.current }, AuthRateLimiter()), log, { settings.current }, { emptyList() },
            { null }, { Long.MAX_VALUE / 4 }, mirrors = mc, webProbeTimeoutMillis = 1_500, httpsOnly = httpsOnly)
        server = make(false)
        port = (server.start(0, "127.0.0.1") as ServerStartResult.Started).port
        strict = make(true)
        strict.start(0, "127.0.0.1")
    }

    @After fun tearDown() { server.stop(0, 100); strict.stop(0, 100); upServer.stop(0, 100) }

    private val upUrl get() = "http://127.0.0.1:$upPort/up.git"
    private val basic = "Basic " + Base64.getEncoder().encodeToString("git:$password".toByteArray())

    /** Raw HTTP so Host / Origin can be set (HttpURLConnection refuses to). */
    private fun post(path: String, body: String, viaPort: Int = port, auth: Boolean = true, type: String = "application/json",
                     host: String = "127.0.0.1:$viaPort", headers: Map<String, String> = emptyMap()): Pair<Int, String> =
        Socket("127.0.0.1", viaPort).use { s ->
            val bytes = body.toByteArray()
            val h = buildString {
                append("POST $path HTTP/1.1\r\nHost: $host\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
                if (auth) append("Authorization: $basic\r\n")
                headers.forEach { (k, v) -> append("$k: $v\r\n") }
                append("\r\n")
            }
            s.getOutputStream().apply { write(h.toByteArray()); write(bytes); flush() }
            val resp = s.getInputStream().readAllBytes().toString(Charsets.UTF_8)
            resp.substringAfter(' ').take(3).toInt() to resp.substringAfter("\r\n\r\n")
        }

    private fun code(r: Pair<Int, String>) = Regex(""""code":"([A-Z_]+)"""").find(r.second)?.groupValues?.get(1)
    private fun add(body: String = """{"url":"$upUrl","name":"web1"}""", vararg extra: Pair<String, String>) = post("/api/v1/mirrors", body, headers = extra.toMap())

    @Test fun previewAndRegisterUseTheAppFlow() {
        val p = post("/api/v1/mirrors/preview", """{"url":"$upUrl"}""", headers = mapOf("Origin" to "http://127.0.0.1:$port", "Sec-Fetch-Site" to "same-origin"))
        assertEquals(p.second, 200, p.first)
        assertTrue(p.second, p.second.contains(""""access":"REACHABLE"""") && p.second.contains(""""refCount":2""") && p.second.contains(""""suggestedName":"up""""))
        val r = add()
        assertEquals(r.second, 201, r.first)
        assertTrue(r.second, r.second.contains(""""name":"web1"""") && r.second.contains(""""syncing":true"""))
        val m = store.require("web1")
        assertEquals(CreatedBy.WEB, m.createdBy); assertEquals(upUrl, m.mirror!!.remoteUrl)
        assertEquals(listOf("web1" to SyncTrigger.INITIAL), enqueued)
        assertTrue(log.entries.value.any { it.action == AccessAction.MIRROR_ADD && it.status == 201 && it.repoName == "web1" })
        // WM-05: the summary says why a queued first fetch waits
        store.updateMirror("web1") { it.copy(waitingForForeground = true) }
        val s = RepoBrowser.summary(store.require("web1"), settings.current, 0)
        assertFalse(s.syncing); assertEquals("FOREGROUND", s.waiting)
        // same URL again
        val again = post("/api/v1/mirrors/preview", """{"url":"$upUrl"}""")
        assertTrue(again.second, again.second.contains(""""existingRepo":"web1"""") && again.second.contains(""""access":null"""))
        val dup = add("""{"url":"$upUrl","name":"web2"}""")
        assertEquals(409, dup.first); assertEquals("ALREADY_MIRRORED", code(dup)); assertTrue(dup.second.contains(""""repo":"web1""""))
    }

    @Test fun rejectionCodes() {
        fun check(status: Int, c: String, r: Pair<Int, String>) { assertEquals(r.second, status, r.first); assertEquals(r.second, c, code(r)) }
        check(400, "HTTPS_ONLY", post("/api/v1/mirrors/preview", """{"url":"$upUrl"}""", viaPort = strict.port))
        check(400, "HTTPS_ONLY", post("/api/v1/mirrors", """{"url":"http://github.com/a/b","name":"x"}""", viaPort = strict.port))
        check(400, "MULTIPLE_URLS", add("""{"url":"https://github.com/a/b\nhttps://github.com/c/d"}"""))
        check(400, "TOKEN_NOT_ACCEPTED", add("""{"url":"$upUrl","name":"x","token":"ghp_secret"}"""))
        check(400, "UNSUPPORTED_URL", add("""{"url":"not a url"}"""))
        check(400, "BAD_REQUEST", add("""["$upUrl"]"""))
        check(400, "INVALID_NAME", add("""{"url":"$upUrl","name":"-bad"}"""))
        store.create("taken", RepoType.LOCAL, CreatedBy.PUSH)
        check(409, "NAME_TAKEN", add("""{"url":"$upUrl","name":"taken"}"""))
        env.free = 1
        check(507, "STORAGE_LOW", add())
        env.free = Long.MAX_VALUE / 4
        check(404, "ORIGIN_NOT_FOUND", add("""{"url":"http://127.0.0.1:$upPort/nope.git","name":"x"}"""))
        assertEquals(listOf("taken"), store.repos.value.map { it.name })
        assertTrue(log.entries.value.any { it.action == AccessAction.MIRROR_ADD && it.code == "TOKEN_NOT_ACCEPTED" })
    }

    /** WM-03: JSON only, same origin, a Host naming the phone. Nothing is created. */
    @Test fun crossSiteRequestsAreRefused() {
        val form = post("/api/v1/mirrors", "url=$upUrl&name=x", type = "application/x-www-form-urlencoded")
        val text = post("/api/v1/mirrors", """{"url":"$upUrl","name":"x"}""", type = "text/plain")
        val origin = add(extra = arrayOf("Origin" to "http://evil.example"))
        val fetchSite = add(extra = arrayOf("Sec-Fetch-Site" to "cross-site"))
        val rebound = post("/api/v1/mirrors", """{"url":"$upUrl","name":"x"}""", host = "evil.example:$port")
        for (r in listOf(form, text, origin, fetchSite, rebound)) { assertEquals(r.second, 403, r.first); assertEquals("CROSS_SITE", code(r)) }
        assertTrue(store.repos.value.isEmpty())
        assertEquals(5, log.entries.value.count { it.action == AccessAction.MIRROR_ADD && it.code == "CROSS_SITE" })
    }

    /** WM-02: push-level auth (D-12), ngrok port always refused before auth (D-13), saved token only with a password (D-14). */
    @Test fun authNgrokAndTokenRules() {
        // "읽기·쓰기" and "push만": a password is needed
        assertEquals(401, post("/api/v1/mirrors/preview", """{"url":"$upUrl"}""", auth = false).first)
        settings.update { it.withAuthLevel(1) }
        assertEquals(401, post("/api/v1/mirrors", """{"url":"$upUrl","name":"x"}""", auth = false).first)
        // ngrok port: 403 whatever the level, no password prompt
        for (level in 0..2) {
            settings.update { it.withAuthLevel(level) }
            val r = post("/api/v1/mirrors", """{"url":"$upUrl","name":"x"}""", viaPort = server.ngrokPort, auth = false)
            assertEquals(403, r.first); assertEquals("EXTERNAL_NOT_ALLOWED", code(r))
        }
        assertTrue(store.repos.value.isEmpty())
        // private origin with a saved token
        upSettings.update { it.copy(authEnabled = true) }
        creds.add("127.0.0.1", upPassword, "git")
        settings.update { it.withAuthLevel(0) }
        assertTrue(post("/api/v1/mirrors/preview", """{"url":"$upUrl"}""").second.contains(""""access":"REACHABLE""""))
        // "끄기": anonymous may add, but never with the phone's token
        settings.update { it.withAuthLevel(2) }
        assertTrue(post("/api/v1/mirrors/preview", """{"url":"$upUrl"}""", auth = false).second.contains(""""access":"TOKEN_REQUIRED""""))
        val r = post("/api/v1/mirrors", """{"url":"$upUrl","name":"priv"}""", auth = false)
        assertEquals(403, r.first); assertEquals("TOKEN_REQUIRED", code(r))
        assertFalse(r.second.contains(upPassword))
        upSettings.update { it.copy(authEnabled = false) }
        assertEquals(201, post("/api/v1/mirrors", """{"url":"$upUrl","name":"pub"}""", auth = false).first)
    }

    /** WM-01 AC3: 2 probes at a time, a stuck origin ends as TIMEOUT. */
    @Test fun probeTimeoutAndBusy() {
        val hang = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val held = mutableListOf<Socket>()
        val t = Thread { runCatching { while (true) held += hang.accept() } }.apply { isDaemon = true; start() }
        try {
            val url = """{"url":"http://127.0.0.1:${hang.localPort}/slow.git"}"""
            val results = (1..3).map { java.util.concurrent.CompletableFuture.supplyAsync { post("/api/v1/mirrors/preview", url) } }.map { it.get() }
            assertEquals(results.toString(), 1, results.count { code(it) == "BUSY" && it.first == 503 })
            assertEquals(results.toString(), 2, results.count { it.second.contains(""""failure":"TIMEOUT"""") })
        } finally { hang.close(); held.forEach { it.close() }; t.interrupt() }
    }

    @Test fun otherWebWritesDoNotExist() {
        assertTrue(post("/api/v1/repos", "{}").first in listOf(404, 405))
        assertNull(log.entries.value.firstOrNull { it.action == AccessAction.MIRROR_ADD })
    }
}
