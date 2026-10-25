package com.ouor.mobilegit.core

import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class UrlAndNameTest {
    @Test fun normalizesHostUrls() {
        fun n(s: String) = RemoteUrls.normalize(s)?.url
        assertEquals("https://github.com/a/b.git", n("https://github.com/a/b"))
        assertEquals("https://github.com/a/b.git", n("github.com/a/b/tree/main/src"))
        assertEquals("https://github.com/a/b.git", n("https://github.com/a/b.git"))
        assertEquals("https://gitlab.com/grp/sub/proj.git", n("https://gitlab.com/grp/sub/proj/-/tree/main"))
        assertEquals("https://gitlab.example.com:8443/grp/proj.git", n("https://gitlab.example.com:8443/grp/proj/-/blob/main/x"))
        assertEquals("https://bitbucket.org/team/repo.git", n("https://bitbucket.org/team/repo/src/master/"))
        assertEquals("https://codeberg.org/u/r.git", n("https://codeberg.org/u/r/src/branch/main"))
        assertNull(n("ftp://x/y")); assertNull(n("https://github.com/onlyowner")); assertNull(n(""))
    }

    @Test fun namesFollowRule() {
        assertTrue(RepoNames.isValid("foo.bar_baz-1"))
        assertFalse(RepoNames.isValid("-x")); assertFalse(RepoNames.isValid("a/b")); assertFalse(RepoNames.isValid("x.git"))
        assertFalse(RepoNames.isValid("a".repeat(101))); assertFalse(RepoNames.isValid(".."))
        val r = RemoteUrls.normalize("https://github.com/own/repo")!!
        assertEquals("repo", RepoNames.suggest(r) { false })
        assertEquals("own-repo", RepoNames.suggest(r) { it == "repo" })
        assertEquals("own-repo-2", RepoNames.suggest(r) { it == "repo" || it == "own-repo" })
    }

    @Test fun extractsSharedUrls() {
        assertEquals(listOf("https://github.com/a/b"), RemoteUrls.extractCandidates("Check this out: https://github.com/a/b."))
    }

    @Test fun redactsSecrets() {
        assertEquals("https://host/x Authorization: Basic *** tok=***", Redact.text("https://u:p@host/x Authorization: Basic abc123 tok=sekrit", listOf("sekrit")))
    }
}

class NetTest {
    @Test fun classifiesAddresses() {
        val ifaces = listOf(NetIface("wlan0", "192.168.0.12"), NetIface("swlan0", "10.42.17.1"), NetIface("tun0", "100.101.2.3"),
            NetIface("rmnet_data0", "10.200.1.1"), NetIface("lo", "127.0.0.1"))
        val a = NetClassifier.addresses(ifaces, setOf("wlan0"), 8080)
        assertEquals(listOf(AddressKind.WIFI, AddressKind.HOTSPOT, AddressKind.TAILSCALE), a.map { it.kind })
        assertEquals("http://192.168.0.12:8080/foo.git", a[0].cloneUrl("foo"))
        assertEquals(AccessRoute.HOTSPOT, NetClassifier.route("10.42.17.1", false, a))
        assertEquals(AccessRoute.LOCALHOST, NetClassifier.route("127.0.0.1", false, a))
        assertEquals(AccessRoute.NGROK, NetClassifier.route("127.0.0.1", true, a))
        assertEquals(AccessRoute.TAILSCALE, NetClassifier.route("100.101.2.3", false, a))
        assertFalse(NetClassifier.isTailscale("100.128.0.1"))
    }
}

class AuthUnitTest {
    @Test fun hashAndVerify() {
        val h = PasswordHasher.hash("password1", 1000)
        assertTrue(PasswordHasher.verify("password1", h)); assertFalse(PasswordHasher.verify("password2", h))
        assertFalse(h.contains("password1"))
    }

    @Test fun firstRunGeneratesPasswordShownOnce() {
        val s = SettingsStore(File(tempDir(), "s.json"), FakeCipher)
        val pw = s.initialPassword()!!
        assertEquals(16, pw.length)
        assertEquals("git", s.current.username)
        assertTrue(PasswordHasher.verify(pw, s.current.passwordHash))
        s.completeOnboarding()
        assertNull(s.initialPassword())
        try { s.setPassword("short"); error("x") } catch (_: SettingsException) {}
        try { s.setPort(80); error("x") } catch (_: SettingsException) {}
    }

    @Test fun networkClassesForSecurity() {
        // SEC-03: anonymous read only from the phone's own networks
        listOf("192.168.0.7", "10.1.2.3", "172.16.0.1", "100.100.1.1", "169.254.3.4", "fe80::1", "fd7a:115c:a1e0::1", "::ffff:192.168.0.7")
            .forEach { assertTrue(it, NetClassifier.isLocalNetwork(it)) }
        listOf("127.0.0.1", "::1", "8.8.8.8", "2001:db8::1", "::ffff:8.8.8.8").forEach { assertFalse(it, NetClassifier.isLocalNetwork(it)) }
        // SEC-04: global IPv6 local addresses are refused; v4-mapped, loopback, link-local and ULA are not
        assertTrue(NetClassifier.isPublicIpv6("2001:4860:1::5"))
        listOf("::ffff:192.168.0.5", "192.168.0.5", "::1", "fe80::1%wlan0", "fd00::5").forEach { assertFalse(it, NetClassifier.isPublicIpv6(it)) }
        // SEC-03: DNS rebinding hosts
        listOf("192.168.0.5:8080", "localhost:8080", "[fe80::1]:8080", "phone", "phone.local:8080", "phone.tail1234.ts.net").forEach { assertTrue(it, NetClassifier.isDirectHost(it)) }
        listOf("evil.example:8080", "evil.example", null, "").forEach { assertFalse("$it", NetClassifier.isDirectHost(it)) }
    }

    @Test fun limiterGroupsIpv6AndStaysBounded() {
        val l = AuthRateLimiter(maxTracked = 100)
        repeat(11) { l.recordFailure("2001:db8:1:2::$it") } // one /64 = one client (SEC-07)
        assertTrue(l.isBlocked("2001:db8:1:2::ffff"))
        assertFalse(l.isBlocked("2001:db8:1:3::1"))
        repeat(5_000) { l.recordFailure("10.0.${it / 250}.${it % 250}") }
        assertTrue(l.trackedCount() <= 102)
    }

    @Test fun ngrokFailuresShareOneLimit() {
        val s = SettingsStore(File(tempDir(), "s.json"), FakeCipher).apply { setPassword("right-password"); update { it.copy(ngrokEnabled = true) } }
        val a = Authenticator({ s.current }, AuthRateLimiter())
        fun basic(pw: String) = "Basic " + java.util.Base64.getEncoder().encodeToString("git:$pw".toByteArray())
        assertEquals(AuthDecision.OK, a.decide(basic("right-password"), "1.1.1.1", false, viaNgrok = true))
        // SEC-01: a new address per attempt does not dodge the limit
        val results = (1..40).map { a.decide(basic("guess$it"), "203.0.113.$it", false, viaNgrok = true) }
        assertEquals(AuthDecision.BLOCKED, results.last())
        assertEquals(AuthDecision.BLOCKED, a.decide(basic("guess-x"), "198.51.100.1", false, viaNgrok = true))
        // the owner's known-good header still works during the block
        assertEquals(AuthDecision.OK, a.decide(basic("right-password"), "198.51.100.2", false, viaNgrok = true))
    }

    @Test fun credentialsOnlyOverHttpsOrLocal() {
        // SEC-05
        assertTrue(CredentialStore.allowsCredentials("https://github.com/a/b.git"))
        assertFalse(CredentialStore.allowsCredentials("http://github.com/a/b.git"))
        assertTrue(CredentialStore.allowsCredentials("http://192.168.0.10:3000/a/b.git"))
        assertTrue(CredentialStore.allowsCredentials("http://127.0.0.1:8080/a.git"))
        val c = CredentialStore(File(tempDir(), "c.json"), FakeCipher)
        c.add("github.com", "tok")
        assertNull(c.effective(MirrorSource("http://github.com/a/b.git")))
        assertNotNull(c.effective(MirrorSource("https://github.com/a/b.git")))
    }

    @Test fun rateLimiterWindow() {
        var t = 0L
        val l = AuthRateLimiter({ t })
        repeat(10) { assertFalse(l.recordFailure("1.2.3.4")) }
        assertTrue(l.recordFailure("1.2.3.4"))
        assertTrue(l.isBlocked("1.2.3.4")); assertFalse(l.isBlocked("5.6.7.8"))
        t += 300_001; assertFalse(l.isBlocked("1.2.3.4"))
        repeat(10) { l.recordFailure("9.9.9.9") }; t += 61_000
        assertFalse(l.recordFailure("9.9.9.9")) // old failures expired
    }
}

class NotificationRulesTest {
    private val ok = MirrorSource("u", originStatus = OriginStatus.OK)
    @Test fun onlyTransitions() {
        val failed = ok.copy(lastError = ErrorCategory.NETWORK)
        assertEquals(listOf(NoticeKind.FAILED), NotificationRules.decide("r", ok, failed, 0, false).map { it.kind })
        assertTrue(NotificationRules.decide("r", failed, failed.copy(lastError = ErrorCategory.TIMEOUT), 0, false).isEmpty())
        assertEquals(listOf(NoticeKind.RECOVERED), NotificationRules.decide("r", failed, ok, 0, false).map { it.kind })
        assertEquals(listOf(NoticeKind.ORIGIN_GONE), NotificationRules.decide("r", ok, ok.copy(originStatus = OriginStatus.GONE, lastError = ErrorCategory.ORIGIN_GONE), 0, false).map { it.kind })
        assertEquals(listOf(NoticeKind.ARCHIVED, NoticeKind.UPDATED), NotificationRules.decide("r", ok, ok, 2, true).map { it.kind })
    }

    @Test fun queryFiltersAndSorts() {
        val s = Settings()
        val a = RepoMeta("1", "beta", RepoType.LOCAL, createdAt = 10, createdBy = CreatedBy.PUSH, lastPushAt = 100)
        val b = RepoMeta("2", "Alpha", RepoType.MIRROR, createdAt = 5, createdBy = CreatedBy.MIRROR_ADD,
            mirror = MirrorSource("u", originStatus = OriginStatus.GONE, lastCheckAt = 6))
        val items = RepoQuery.items(listOf(a, b), s, 7, emptyMap())
        assertEquals(listOf("Alpha", "beta"), RepoQuery.apply(items, RepoSort.NAME, RepoFilter.ALL, "").map { it.meta.name })
        assertEquals(listOf("beta", "Alpha"), RepoQuery.apply(items, RepoSort.RECENT, RepoFilter.ALL, "").map { it.meta.name })
        assertEquals(listOf("Alpha"), RepoQuery.apply(items, RepoSort.NAME, RepoFilter.PROBLEM, "").map { it.meta.name })
        assertEquals(listOf("beta"), RepoQuery.apply(items, RepoSort.NAME, RepoFilter.ALL, "ET").map { it.meta.name })
    }
}

class RemoteCatalogTest {
    @Test fun githubPaginationAndRateLimit() = runBlocking {
        val hits = AtomicInteger()
        var port = 0
        val srv = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/users/octo/repos") {
                    val page = call.request.queryParameters["page"] ?: "1"
                    if (page == "1" && hits.getAndIncrement() == 0) {
                        call.response.header("X-RateLimit-Remaining", "0"); call.response.header("Retry-After", "1")
                        call.respondText("{}", status = HttpStatusCode.Forbidden); return@get
                    }
                    if (page == "1") call.response.header("Link", "<http://127.0.0.1:$port/users/octo/repos?per_page=100&type=owner&page=2>; rel=\"next\"")
                    val name = if (page == "1") "one" else "two"
                    call.respondText("""[{"name":"$name","full_name":"octo/$name","description":null,"clone_url":"https://github.com/octo/$name.git","size":2,"fork":${page == "2"},"archived":false,"private":false}]""")
                }
            }
        }.start(wait = false)
        port = srv.engine.resolvedConnectors().first().port
        try {
            val waits = mutableListOf<Long>()
            val list = RemoteCatalog(apiBaseOverride = "http://127.0.0.1:$port").list(BulkSourceKind.GITHUB_USER, "octo", onRateLimited = { waits += it })
            assertEquals(listOf("one", "two"), list.map { it.name })
            assertEquals(2048L, list[0].sizeBytes)
            assertTrue(list[1].fork)
            assertTrue(waits.first() > 0); assertEquals(0L, waits.last())
        } finally { srv.stop(0, 0) }
    }

    @Test fun gitlabGroup() = runBlocking {
        val srv = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/groups/{g}/projects") {
                    assertEquals("tok", call.request.header("PRIVATE-TOKEN"))
                    call.respondText("""[{"path":"p","path_with_namespace":"g/p","description":"d","http_url_to_repo":"https://gitlab.com/g/p.git","archived":true,"forked_from_project":{"id":1},"visibility":"private"}]""")
                }
            }
        }.start(wait = false)
        val port = srv.engine.resolvedConnectors().first().port
        try {
            val r = RemoteCatalog(apiBaseOverride = "http://127.0.0.1:$port").list(BulkSourceKind.GITLAB_GROUP, "g", token = "tok").single()
            assertTrue(r.archived && r.fork && r.isPrivate)
            assertNull(r.sizeBytes)
        } finally { srv.stop(0, 0) }
    }

    @Test fun rateLimitHeaders() {
        val c = RemoteCatalog({ 1_000_000L })
        assertEquals(1_000_000L + 30_000, c.rateLimitUntil(429, "30", null, null))
        assertEquals(1_700_000_000_000L, c.rateLimitUntil(403, null, "0", "1700000000"))
        assertNull(c.rateLimitUntil(403, null, "10", "1700000000"))
    }
}

class TunnelManagerTest {
    @Test fun reconnectsWithBackoffAndStopsOnBadToken() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val calls = AtomicInteger()
        val drop = CountDownLatch(1)
        val backend = TunnelBackend { token, port ->
            if (token == "bad") throw TunnelAuthException("ERR_NGROK_105")
            if (calls.incrementAndGet() == 1) throw java.io.IOException("network down")
            object : TunnelSession {
                override val publicUrl = "https://abc.ngrok.app"
                override fun awaitClosed() { drop.await() }
                override fun close() { drop.countDown() }
            }
        }
        val m = TunnelManager(backend, scope, baseDelayMs = 10, maxDelayMs = 40)
        assertEquals(40, m.backoff(10))
        m.start("good", 8080)
        withTimeout(5_000) { m.state.first { it is TunnelState.Connected } }
        assertEquals("https://abc.ngrok.app", m.publicUrl)
        m.stop()
        assertEquals(TunnelState.Off, m.state.value)
        m.start("bad", 8080)
        withTimeout(5_000) { m.state.first { it is TunnelState.TokenRejected } }
        scope.cancel()
    }
}

// QA-04: GitHub-like safe HTML subset in Markdown (jsoup allowlist).
class MarkdownSanitizeTest {
    private fun r(md: String) = RepoBrowser.renderMarkdown(md)

    @Test fun safeRawHtmlIsRendered() {
        val html = r("""<h1 align="center"><br><img src="media/logo.png" width="200" alt="logo"></h1>""" + "\n\n" +
            "<details open><summary>More</summary>body</details>")
        assertTrue(html, html.contains("""<h1 align="center">"""))
        assertTrue(html, html.contains("""src="media/logo.png"""") && html.contains("""width="200""""))
        assertTrue(html, html.contains("<details open") && html.contains("<summary>More</summary>"))
        assertFalse(html.contains("&lt;h1"))
    }

    @Test fun dangerousHtmlIsRemoved() {
        val html = r("""<img src="x.png" onerror="alert(1)"><script>alert(2)</script><a href="javascript:alert(3)">x</a>""" +
            """<iframe src="https://evil"></iframe><p style="position:fixed" class="btn">y</p><form action="/x"><input></form>""" +
            "\n\n[md](javascript:alert(4)) [rel](docs/a.md) [anchor](#usage)\n\n```kotlin\nval a = 1\n```")
        for (bad in listOf("onerror", "<script", "alert(2)", "javascript:", "<iframe", "style=", "class=\"btn\"", "<form", "<input"))
            assertFalse("$bad in $html", html.contains(bad))
        assertTrue(html, html.contains("""href="docs/a.md"""") && html.contains("""href="#usage""""))
        assertTrue(html, html.contains("""<code class="language-kotlin">"""))
    }
}

// QA-08: each rule gives its own reason.
class NameReasonTest {
    @Test fun reasons() {
        assertTrue(RepoNames.validate("-bad")!!.contains("시작"))
        assertTrue(RepoNames.validate("a".repeat(101))!!.contains("101자"))
        assertTrue(RepoNames.validate("foo bar")!!.contains("공백"))
        assertTrue(RepoNames.validate("x.git")!!.contains(".git"))
        assertNull(RepoNames.validate("ok-name_1.2"))
    }
}
