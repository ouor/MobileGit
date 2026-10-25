package com.ouor.mobilegit.core

import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64

class GitServerTest {
    private lateinit var root: File
    private lateinit var store: RepoStore
    private lateinit var settings: SettingsStore
    private lateinit var log: AccessLogStore
    private lateinit var server: GitServer
    private var port = 0
    @Volatile private var tunnelUp = false
    private val password = "s3cret-pass"

    @Before fun setUp() {
        JGitEnv.init()
        root = tempDir()
        store = RepoStore(root)
        settings = SettingsStore(File(root, "settings.json"), FakeCipher)
        settings.setPassword(password)
        log = AccessLogStore(null)
        val auth = Authenticator({ settings.current }, AuthRateLimiter())
        val web = File(root, "web").apply { mkdirs(); File(this, "index.html").writeText("<html>ui</html>"); File(this, "app.js").writeText("1"); File(this, "assets").mkdirs(); File(this, "assets/index-abc123.js").writeText("console.log(1);".repeat(200)) }
        server = GitServer(store, auth, log, { settings.current }, { emptyList() },
            { p -> File(web, p).takeIf { it.isFile }?.inputStream() }, { Long.MAX_VALUE / 4 }, tunnelConnected = { tunnelUp })
        val r = server.start(0, "127.0.0.1")
        port = (r as ServerStartResult.Started).port
    }

    @After fun tearDown() { server.stop(0, 100) }

    private fun url(repo: String, user: String = "git", pw: String = password) = "http://$user:$pw@127.0.0.1:$port/$repo"

    private fun get(path: String, auth: Boolean = true, viaPort: Int = port): Pair<Int, String> {
        val c = URI("http://127.0.0.1:$viaPort$path").toURL().openConnection() as HttpURLConnection
        if (auth) c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("git:$password".toByteArray()))
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        return code to body
    }

    @Test fun pushCreatesRepo_thenCloneV2AndV0_andHeadPrefersMain() {
        assumeTrue("git CLI required", GitCli.available)
        val work = Commits.initWork(branch = "feature")
        Commits.commit(work, "README.md", "# Hello\n\nworld")
        work.branchCreate().setName("main").call()
        val dir = work.repository.workTree
        GitCli.ok(dir, "push", url("client-a.git"), "feature", "main")

        val meta = store.get("client-a")
        assertNotNull(meta); assertEquals(RepoType.LOCAL, meta!!.type); assertEquals(CreatedBy.PUSH, meta.createdBy)
        assertEquals("main", store.open("client-a").use { it.exactRef("HEAD").target.name.removePrefix("refs/heads/") })
        assertNotNull(store.get("client-a")!!.lastPushAt)

        val c2 = File(tempDir(), "c2")
        GitCli.ok(root, "-c", "protocol.version=2", "clone", url("client-a.git"), c2.absolutePath)
        assertEquals("# Hello\n\nworld", File(c2, "README.md").readText().replace("\r\n", "\n"))
        val c0 = File(tempDir(), "c0")
        GitCli.ok(root, "-c", "protocol.version=0", "clone", url("client-a"), c0.absolutePath) // F-01 AC5: without .git
        assertTrue(File(c0, "README.md").isFile)

        // second push (fetch/pull path)
        work.checkout().setName("main").call()
        Commits.commit(work, "a.txt", "more")
        GitCli.ok(dir, "push", url("client-a.git"), "main")
        GitCli.ok(c2, "pull", "--ff-only")
        assertTrue(File(c2, "a.txt").isFile)
        assertTrue(log.entries.value.toString(), log.entries.value.any { it.action == AccessAction.PUSH && it.authResult == AuthResult.OK })
    }

    @Test fun invalidNameRejected() {
        assumeTrue(GitCli.available)
        val work = Commits.initWork(); Commits.commit(work, "x", "y")
        val r = GitCli.run(work.repository.workTree, "push", url("-bad.git"), "main")
        assertNotEquals(0, r.code)
        assertTrue(store.repos.value.isEmpty())
    }

    @Test fun pushToMirrorIsRejectedWithMessage() {
        assumeTrue(GitCli.available)
        store.create("mirrored", RepoType.MIRROR, CreatedBy.MIRROR_ADD, mirror = MirrorSource("https://example.com/a/b.git"))
        val work = Commits.initWork(); Commits.commit(work, "x", "y")
        val r = GitCli.run(work.repository.workTree, "-c", "credential.helper=", "push", url("mirrored.git"), "main")
        assertNotEquals(0, r.code)
        assertTrue(r.out, r.out.contains("read-only mirror of https://example.com/a/b.git"))
        assertTrue(log.entries.value.any { it.repoName == "mirrored" && it.action == AccessAction.PUSH && it.status == 403 })
    }

    @Test fun authRules() {
        assumeTrue(GitCli.available)
        store.create("pub", RepoType.LOCAL, CreatedBy.APP_EMPTY)
        // wrong password
        assertNotEquals(0, GitCli.run(root, "-c", "credential.helper=", "ls-remote", url("pub.git", pw = "nope")).code)
        // no credentials
        assertEquals(401, get("/pub.git/info/refs?service=git-upload-pack", auth = false).first)
        // anonymous LAN read on: loopback is not "the same network" (SEC-03: a local proxy/tunnel would make it anyone)
        settings.update { it.copy(anonymousReadOnLan = true) }
        assertEquals(401, get("/pub.git/info/refs?service=git-upload-pack", auth = false).first)
        assertEquals(401, get("/api/v1/repos", auth = false).first)
        // the rule itself (F-03 AC5): read ok for a LAN client that typed the phone's address, push still needs auth
        val a = Authenticator({ settings.current }, AuthRateLimiter())
        assertEquals(AuthDecision.ANONYMOUS, a.decide(null, "192.168.0.7", isPush = false, viaNgrok = false, anonymousOk = true))
        assertEquals(AuthDecision.CHALLENGE, a.decide(null, "192.168.0.7", isPush = true, viaNgrok = false, anonymousOk = true))
        // R2-06: ngrok follows the user's choice (no silent forcing). "push만" over ngrok: anonymous read, push 401
        val ng = server.ngrokPort
        // R2SEC-01: ngrok off (or on but not connected) -> the ngrok loopback port is plain loopback: no anonymous read
        assertEquals(401, get("/pub.git/info/refs?service=git-upload-pack", auth = false, viaPort = ng).first)
        assertEquals(401, get("/api/v1/repos", auth = false, viaPort = ng).first)
        settings.update { it.copy(ngrokEnabled = true) }
        assertEquals(401, get("/pub.git/info/refs?service=git-upload-pack", auth = false, viaPort = ng).first)
        tunnelUp = true
        assertTrue(settings.current.exposedWithoutPassword)
        assertEquals(200, get("/pub.git/info/refs?service=git-upload-pack", auth = false, viaPort = ng).first)
        assertEquals(401, get("/pub.git/info/refs?service=git-receive-pack", auth = false, viaPort = ng).first)
        assertEquals(401, get("/pub.git/info/refs?service=git-upload-pack", auth = false).first) // loopback still not anonymous
        assertTrue(log.entries.value.any { it.route == AccessRoute.NGROK && it.authResult == AuthResult.ANONYMOUS })
        // "읽기·쓰기" with ngrok: password everywhere
        settings.update { it.copy(anonymousReadOnLan = false) }
        assertFalse(settings.current.exposedWithoutPassword)
        assertEquals(401, get("/pub.git/info/refs?service=git-upload-pack", auth = false, viaPort = ng).first)
        // "끄기": everything anonymous, ngrok included
        settings.update { it.copy(authEnabled = false) }
        assertTrue(settings.current.exposedWithoutPassword)
        assertEquals(200, get("/pub.git/info/refs?service=git-receive-pack", auth = false, viaPort = ng).first)
        settings.update { it.copy(ngrokEnabled = false) }
        assertEquals(200, get("/pub.git/info/refs?service=git-receive-pack", auth = false).first)
    }

    @Test fun bruteForceBlocksIp() {
        repeat(11) {
            val c = URI("http://127.0.0.1:$port/api/v1/repos").toURL().openConnection() as HttpURLConnection
            c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("git:wrong$it".toByteArray()))
            c.responseCode
        }
        assertEquals(429, get("/api/v1/repos").first) // even correct creds are blocked for 5 min
        assertTrue(log.entries.value.any { it.authResult == AuthResult.BLOCKED })
    }

    @Test fun webApiAndStatic() {
        assumeTrue(GitCli.available)
        val work = Commits.initWork()
        Commits.commit(work, "README.md", "# Title\n\n<script>alert(1)</script>\n\n[x](javascript:alert(1))")
        Commits.commit(work, "src/main.kt", "fun main() {}")
        Commits.commit(work, "bin.dat", "\u0000\u0001\u0002binary")
        Commits.commit(work, "big.lfs", "version https://git-lfs.github.com/spec/v1\noid sha256:${"a".repeat(64)}\nsize 12345\n")
        GitCli.ok(work.repository.workTree, "push", url("demo.git"), "main")

        val (c1, list) = get("/api/v1/repos"); assertEquals(200, c1); assertTrue(list.contains("\"name\":\"demo\""))
        val (_, detail) = get("/api/v1/repos/demo"); assertTrue(detail, detail.contains("\"cloneUrl\":\"http://127.0.0.1:$port/demo.git\""))
        val (_, tree) = get("/api/v1/repos/demo/tree"); assertTrue(tree.indexOf("\"src\"") < tree.indexOf("\"README.md\""))
        // WR-7: the pointer is flagged; a small ordinary file is not
        val entries = CoreJson.decodeFromString(TreeDto.serializer(), tree).entries.associateBy { it.name }
        assertTrue(entries["big.lfs"]!!.lfs); assertFalse(entries["bin.dat"]!!.lfs); assertFalse(entries["src"]!!.lfs)
        val (_, sub) = get("/api/v1/repos/demo/tree?path=src"); assertTrue(sub.contains("src/main.kt"))
        val (_, blob) = get("/api/v1/repos/demo/blob?path=src/main.kt"); assertTrue(blob.contains("fun main()"))
        val (_, bin) = get("/api/v1/repos/demo/blob?path=bin.dat"); assertTrue(bin, bin.contains("\"binary\":true"))
        val (_, lfs) = get("/api/v1/repos/demo/blob?path=big.lfs"); assertTrue(lfs.contains("\"size\":12345"))
        val (_, readme) = get("/api/v1/repos/demo/readme")
        val html = readme.substringAfter("\"html\"")
        assertTrue(html.contains("<h1>Title</h1>")); assertFalse(html.contains("<script>")); assertFalse(readme.contains("href=\\\"javascript"))
        val (_, commits) = get("/api/v1/repos/demo/commits?perPage=2"); assertTrue(commits.contains("\"hasMore\":true"))
        val (_, pathLog) = get("/api/v1/repos/demo/commits?path=src"); assertEquals(1, Regex("\"sha\"").findAll(pathLog).count())
        assertEquals(404, get("/api/v1/repos/nope").first)
        assertEquals(404, get("/api/v1/repos/demo/tree?ref=nobranch").first)

        val c = URI("http://127.0.0.1:$port/api/v1/repos/demo/raw?path=src/main.kt").toURL().openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("git:$password".toByteArray()))
        assertEquals("fun main() {}", c.inputStream.bufferedReader().readText())
        assertTrue(c.getHeaderField("Content-Disposition").startsWith("attachment"))
        assertEquals("nosniff", c.getHeaderField("X-Content-Type-Options"))

        assertEquals("<html>ui</html>", get("/").second)
        assertEquals("<html>ui</html>", get("/demo/tree/main").second) // SPA fallback
        assertEquals(404, get("/missing.js").first)
    }

    @Test fun emptyRepoApi() {
        store.create("empty", RepoType.LOCAL, CreatedBy.APP_EMPTY)
        val (code, body) = get("/api/v1/repos/empty/tree")
        assertEquals(404, code); assertTrue(body.contains("EMPTY_REPO"))
        assertTrue(get("/api/v1/repos/empty").second.contains("\"empty\":true"))
    }

    @Test fun portInUse() {
        val other = GitServer(store, Authenticator({ settings.current }, AuthRateLimiter()), log, { settings.current }, { emptyList() }, { null }, { 0 })
        assertEquals(ServerStartResult.PortInUse(port), other.start(port, "127.0.0.1"))
    }

    // QA-01: on Android an uncaught exception on any thread kills the process, so a failed bind must not leak
    // out of CIO's accept coroutine. Also covers a port held by a non-Ktor socket.
    @Test fun portInUseLeaksNoUncaughtException() {
        val leaked = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> leaked += e }
        try {
            java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).use { busy ->
                repeat(3) {
                    val other = GitServer(store, Authenticator({ settings.current }, AuthRateLimiter()), log, { settings.current }, { emptyList() }, { null }, { 0 })
                    assertEquals(ServerStartResult.PortInUse(busy.localPort), other.start(busy.localPort, "127.0.0.1"))
                    assertFalse(other.isRunning)
                }
            }
            Thread.sleep(500)
            assertEquals(emptyList<Throwable>(), leaked.toList())
        } finally { Thread.setDefaultUncaughtExceptionHandler(prev) }
    }

    private val CRLF = "\r\n"

    /** Raw HTTP/1.1 exchange so framing headers are seen exactly as sent (HttpURLConnection hides them). */
    private fun rawGet(path: String, vararg extra: String): Pair<String, Map<String, String>> {
        java.net.Socket("127.0.0.1", port).use { sock ->
            val auth = Base64.getEncoder().encodeToString("git:$password".toByteArray())
            val req = (listOf("GET $path HTTP/1.1", "Host: 127.0.0.1:$port", "Authorization: Basic $auth", "Connection: close") + extra).joinToString(CRLF) + CRLF + CRLF
            sock.getOutputStream().write(req.toByteArray())
            val head = sock.getInputStream().readBytes().toString(Charsets.ISO_8859_1).substringBefore(CRLF + CRLF).split(CRLF)
            val headers = head.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            return head[0] to headers
        }
    }

    // WR-2 (no endpoint sends Content-Length together with chunked), WR-1, WR-3, WR-4.
    @Test fun webFramingSpaCacheAndGzip() {
        assumeTrue(GitCli.available)
        val work = Commits.initWork()
        Commits.commit(work, "README.md", "# T\n" + "lorem ipsum ".repeat(300))
        GitCli.ok(work.repository.workTree, "push", url("demo.git"), "main")

        val paths = listOf("/api/v1/repos", "/api/v1/repos/demo", "/api/v1/repos/demo/readme", "/api/v1/repos/demo/raw?path=README.md",
            "/demo.git/info/refs?service=git-upload-pack", "/", "/assets/index-abc123.js", "/r/demo/blob/README.md")
        for (p in paths) for (gz in listOf(false, true)) {
            val (status, h) = if (gz) rawGet(p, "Accept-Encoding: gzip") else rawGet(p)
            assertTrue("$p $status", status.contains(" 200 "))
            assertFalse("$p sends both Content-Length and chunked", "content-length" in h && "transfer-encoding" in h)
        }
        // WR-2: raw has an exact length and is never compressed
        val (_, raw) = rawGet("/api/v1/repos/demo/raw?path=README.md", "Accept-Encoding: gzip")
        assertEquals(("# T\n" + "lorem ipsum ".repeat(300)).length.toString(), raw["content-length"])
        assertNull(raw["content-encoding"])
        // WR-4: JSON and static files are gzipped on request; git smart HTTP is not
        assertEquals("gzip", rawGet("/api/v1/repos/demo/readme", "Accept-Encoding: gzip").second["content-encoding"])
        assertEquals("gzip", rawGet("/assets/index-abc123.js", "Accept-Encoding: gzip").second["content-encoding"])
        assertNull(rawGet("/demo.git/info/refs?service=git-upload-pack", "Accept-Encoding: gzip").second["content-encoding"])
        assertNull(rawGet("/api/v1/repos/demo/readme").second["content-encoding"])
        // WR-3: hashed assets cached for a year; index.html revalidated
        assertEquals("public, max-age=31536000, immutable", rawGet("/assets/index-abc123.js").second["cache-control"])
        assertNull(rawGet("/app.js").second["cache-control"])
        assertEquals("no-cache", rawGet("/r/demo").second["cache-control"])
        // WR-1: dotted paths under /r/ fall back to index.html; elsewhere they are still 404; git still works
        assertEquals("<html>ui</html>", get("/r/demo/blob/README.md").second)
        assertEquals("<html>ui</html>", get("/r/foo.js").second)
        assertEquals("<html>ui</html>", get("/r").second)
        assertEquals(404, get("/missing.js").first)
        assertTrue(get("/api/v1/repos/demo/raw?path=README.md").second.startsWith("# T"))
        GitCli.ok(tempDir(), "clone", url("demo.git"), "c")
    }

    // WR-5: readme?path=<file.md> renders that file with the README renderer (sanitized); directories behave as before.
    @Test fun readmeRendersAnyMarkdownFile() {
        assumeTrue(GitCli.available)
        val work = Commits.initWork()
        Commits.commit(work, "README.md", "# Root")
        Commits.commit(work, "docs/guide.md", "# Guide\n\n<script>x</script>\n\n![img](img/a.png) [next](other.md) [x](javascript:alert(1))")
        Commits.commit(work, "docs/notes.txt", "plain")
        Commits.commit(work, "docs/big.md", "a".repeat(1_100_000))
        GitCli.ok(work.repository.workTree, "push", url("demo.git"), "main")

        val (code, guide) = get("/api/v1/repos/demo/readme?path=docs/guide.md")
        assertEquals(200, code)
        assertTrue(guide, guide.contains("\"path\":\"docs/guide.md\"") && guide.contains("\"format\":\"markdown\""))
        val html = guide.substringAfter("\"html\"")
        assertTrue(html.contains("<h1>Guide</h1>"))
        assertTrue(html, html.contains("src=\\\"img/a.png\\\"") && html.contains("href=\\\"other.md\\\"")) // relative URLs untouched, like README
        assertFalse(html.contains("<script>")); assertFalse(html.contains("javascript:"))
        assertTrue(get("/api/v1/repos/demo/readme?path=").second.contains("\"path\":\"README.md\"")) // directory: unchanged
        assertTrue(get("/api/v1/repos/demo/readme?path=docs").second.contains("\"error\"")) // docs has no README
        val (c400, notMd) = get("/api/v1/repos/demo/readme?path=docs/notes.txt"); assertEquals(400, c400); assertTrue(notMd.contains("NOT_MARKDOWN"))
        val (cBig, big) = get("/api/v1/repos/demo/readme?path=docs/big.md"); assertEquals(400, cBig); assertTrue(big.contains("TOO_LARGE"))
        assertEquals(404, get("/api/v1/repos/demo/readme?path=docs/missing.md").first)
    }

    // QA-05: real git-lfs client: the push stops with our guidance, and GIT_LFS_SKIP_PUSH=1 pushes the pointers.
    @Test fun lfsPushGetsGuidanceAndPointerPushWorks() {
        assumeTrue(GitCli.available && GitCli.run(File("."), "lfs", "version").code == 0)
        val dir = tempDir("lfs")
        GitCli.ok(dir, "init", "-b", "main")
        GitCli.ok(dir, "lfs", "install", "--local")
        GitCli.ok(dir, "lfs", "track", "*.bin")
        File(dir, "a.bin").writeBytes(ByteArray(4096) { it.toByte() })
        GitCli.ok(dir, "add", ".gitattributes", "a.bin")
        GitCli.ok(dir, "commit", "-m", "lfs")
        val denied = GitCli.run(dir, "-c", "credential.helper=", "push", url("lfsrepo.git"), "main")
        assertNotEquals(denied.out, 0, denied.code)
        assertTrue(denied.out, denied.out.contains("GIT_LFS_SKIP_PUSH"))
        val ok = GitCli.run(dir, "-c", "credential.helper=", "push", url("lfsrepo.git"), "main", env = mapOf("GIT_LFS_SKIP_PUSH" to "1"))
        assertEquals(ok.out, 0, ok.code)
        // derived facts are refreshed right after the response is written: wait briefly
        val deadline = System.currentTimeMillis() + 5_000
        while (store.get("lfsrepo")?.hasLfs != true && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(store.require("lfsrepo").hasLfs)
    }

    // QA-08: the git client shows why a name is invalid.
    @Test fun invalidNamePushShowsReason() {
        assumeTrue(GitCli.available)
        val work = Commits.initWork()
        Commits.commit(work, "a.txt", "1")
        for ((name, why) in listOf("-bad" to "시작", "x.git" to ".git", "a".repeat(101) to "101")) {
            val r = GitCli.run(work.repository.workTree, "-c", "credential.helper=", "push", url("$name.git"), "main")
            assertNotEquals(0, r.code)
            assertTrue(r.out, r.out.contains("Invalid repository name") && r.out.contains(why))
        }
    }

    // QA-14: images inline with their type (SVG sandboxed); other files stay opaque downloads; ?download=1 forces one.
    @Test fun rawContentTypes() {
        assumeTrue(GitCli.available)
        val work = Commits.initWork()
        Commits.commit(work, "media/logo.svg", """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script><rect width="1" height="1"/></svg>""")
        Commits.commit(work, "media/a.png", "png")
        Commits.commit(work, "page.html", "<script>alert(1)</script>")
        Commits.commit(work, "notes.txt", "hi")
        GitCli.ok(work.repository.workTree, "push", url("demo.git"), "main")
        fun raw(path: String, extra: String = "") = rawGet("/api/v1/repos/demo/raw?path=$path$extra").second
        val svg = raw("media/logo.svg")
        assertEquals("image/svg+xml", svg["content-type"])
        assertTrue(svg["content-disposition"]!!.startsWith("inline"))
        // local filtering proxies (e.g. AdGuard on the dev PC) may rewrite CSP on the wire, so check the sent policy and its presence
        assertTrue(svg["content-security-policy"]!!.contains("sandbox"))
        assertTrue(GitServer.RAW_CSP.endsWith("sandbox") && "script-src" !in GitServer.RAW_CSP && GitServer.RAW_CSP.startsWith("default-src 'none'"))
        assertEquals("nosniff", svg["x-content-type-options"])
        assertEquals("image/png", raw("media/a.png")["content-type"])
        for (p in listOf("page.html", "notes.txt")) {
            val h = raw(p)
            assertEquals(p, "application/octet-stream", h["content-type"])
            assertTrue(p, h["content-disposition"]!!.startsWith("attachment"))
        }
        val forced = raw("media/logo.svg", "&download=1")
        assertEquals("application/octet-stream", forced["content-type"])
        assertTrue(forced["content-disposition"]!!.startsWith("attachment"))
    }

    // CR-7: accepted pushes are recorded per ref, newest first, with force/delete and route; capped per repo.
    @Test fun pushEventsAreRecorded() {
        assumeTrue(GitCli.available)
        val work = Commits.initWork()
        val c1 = Commits.commit(work, "a.txt", "1")
        val dir = work.repository.workTree
        GitCli.ok(dir, "push", url("demo.git"), "main", "main:refs/heads/dev")
        val c2 = Commits.commit(work, "a.txt", "2")
        GitCli.ok(dir, "push", url("demo.git"), "main")
        GitCli.ok(dir, "push", "--force", url("demo.git"), "$c1:refs/heads/main", ":refs/heads/dev")
        val deadline = System.currentTimeMillis() + 5_000 // written right after the response
        while (store.pushEvents("demo").size < 5 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val ev = kotlinx.coroutines.runBlocking { store.pushEventsFlow("demo").first() }
        assertEquals(5, ev.size)
        val (forced, deleted) = ev.take(2).partition { it.ref == "refs/heads/main" }.let { it.first.single() to it.second.single() }
        assertTrue(forced.forced); assertEquals(c2, forced.oldSha); assertEquals(c1, forced.newSha); assertEquals("main", forced.branch)
        assertEquals("refs/heads/dev", deleted.ref); assertNull(deleted.newSha)
        assertEquals(c2, ev[2].newSha); assertFalse(ev[2].forced)
        assertTrue(ev.drop(3).all { it.oldSha == null && it.newSha == c1 })
        assertEquals(AccessRoute.LOCALHOST, ev.first().route)

        store.appendPushEvents("demo", List(250) { PushEvent(it.toLong(), "refs/heads/x", null, c1, AccessRoute.WIFI, "1.2.3.4") })
        assertEquals(RepoStore.MAX_PUSH_EVENTS, store.pushEvents("demo").size)
    }

    // CR-6: new installs boot the server automatically; a saved value is kept as is.
    @Test fun startOnBootDefault() {
        assertTrue(Settings().startOnBoot)
        assertTrue(CoreJson.decodeFromString(Settings.serializer(), "{}").startOnBoot)
        val f = File(tempDir(), "s.json").apply { writeText("""{"startOnBoot":false,"port":8080}""") }
        assertFalse(SettingsStore(f, FakeCipher).current.startOnBoot)
    }

    @Test fun compressionScope() {
        assertTrue(GitServer.isCompressible("/api/v1/repos"))
        assertTrue(GitServer.isCompressible("/assets/index-abc.js"))
        assertTrue(GitServer.isCompressible("/r/demo/info/refs/x"))
        assertFalse(GitServer.isCompressible("/api/v1/repos/demo/raw"))
        assertFalse(GitServer.isCompressible("/demo.git/info/refs"))
        assertFalse(GitServer.isCompressible("/demo/git-upload-pack"))
        assertFalse(GitServer.isCompressible("/demo/git-receive-pack"))
    }

    // ---- code review round 1 ----

    @Test fun archiveRefsAreReadOnlyForPushes() {
        assumeTrue(GitCli.available)
        val work = Commits.initWork()
        val c1 = Commits.commit(work, "a.txt", "1")
        val dir = work.repository.workTree
        GitCli.ok(dir, "push", url("keep.git"), "main")
        store.open("keep").use { r -> r.updateRef("refs/archive/20250101T000000Z/heads/old").apply { setNewObjectId(org.eclipse.jgit.lib.ObjectId.fromString(c1)) }.update() }
        // GIT-07: `push --mirror` from a clone without refs/archive must not delete them
        Commits.commit(work, "a.txt", "2")
        GitCli.run(dir, "-c", "credential.helper=", "push", "--mirror", url("keep.git"))
        assertNotNull(store.open("keep").use { it.exactRef("refs/archive/20250101T000000Z/heads/old") })
        val r = GitCli.run(dir, "-c", "credential.helper=", "push", url("keep.git"), "main:refs/archive/mine")
        assertNotEquals(0, r.code)
        assertTrue(r.out, r.out.contains("refs/archive/ is read-only"))
    }

    @Test fun zeroPaddedTreeModeIsAccepted() {
        assumeTrue(GitCli.available)
        val work = Commits.initWork()
        Commits.commit(work, "a.txt", "1")
        val repo = work.repository
        val head = repo.resolve("HEAD")
        val id = repo.newObjectInserter().use { ins ->
            val blob = ins.insert(org.eclipse.jgit.lib.Constants.OBJ_BLOB, "x".toByteArray())
            val sub = ins.insert(org.eclipse.jgit.lib.TreeFormatter().apply { append("f", org.eclipse.jgit.lib.FileMode.REGULAR_FILE, blob) })
            val raw = java.io.ByteArrayOutputStream().apply { write("040000 d".toByteArray()); write(0); sub.copyRawTo(this) }.toByteArray()
            val tree = ins.insert(org.eclipse.jgit.lib.Constants.OBJ_TREE, raw) // GIT-06: legacy zero-padded mode
            val who = org.eclipse.jgit.lib.PersonIdent("Old", "old@example.com")
            ins.insert(org.eclipse.jgit.lib.CommitBuilder().apply { setTreeId(tree); setParentId(head); setAuthor(who); setCommitter(who); setMessage("old") })
                .also { ins.flush() }
        }
        repo.updateRef("refs/heads/main").apply { setNewObjectId(id) }.forceUpdate()
        val r = GitCli.run(repo.workTree, "-c", "credential.helper=", "push", url("legacy.git"), "main")
        assertEquals(r.out, 0, r.code)
        assertEquals(id.name, store.open("legacy").use { it.resolve("refs/heads/main").name })
    }

    @Test fun headFollowsPushedBranchWhenDefaultIsMissing() {
        assumeTrue(GitCli.available)
        store.create("empty", RepoType.LOCAL, CreatedBy.APP_EMPTY) // HEAD -> main (does not exist yet)
        val work = Commits.initWork(branch = "master")
        Commits.commit(work, "a.txt", "1")
        GitCli.ok(work.repository.workTree, "push", url("empty.git"), "master")
        assertEquals("refs/heads/master", store.open("empty").use { it.exactRef("HEAD").target.name }) // GIT-10
    }

    @Test fun commitPagesAreBounded() {
        assumeTrue(GitCli.available)
        val work = Commits.initWork()
        Commits.commit(work, "a.txt", "1")
        GitCli.ok(work.repository.workTree, "push", url("hist.git"), "main")
        val (code, body) = get("/api/v1/repos/hist/commits?page=1001")
        assertEquals(400, code); assertTrue(body.contains("PAGE_TOO_DEEP"))
        assertEquals(200, get("/api/v1/repos/hist/commits?page=1000").first)
    }
}
