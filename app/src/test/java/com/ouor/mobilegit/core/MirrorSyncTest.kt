package com.ouor.mobilegit.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * End-to-end mirror tests: an "upstream" mobilegit server (real smart HTTP) is mirrored by a second store.
 * Covers F-06, F-07, F-09, F-10, F-11, F-12, F-19, F-20, F-21.
 */
class MirrorSyncTest {
    private lateinit var upStore: RepoStore
    private lateinit var upSettings: SettingsStore
    private lateinit var upServer: GitServer
    private var upPort = 0
    private val upPassword = "upstream-pass"

    private lateinit var store: RepoStore
    private lateinit var creds: CredentialStore
    private lateinit var settings: SettingsStore
    private lateinit var coordinator: SyncCoordinator
    private lateinit var mirrors: MirrorController
    private lateinit var ops: RepoOps
    private lateinit var bulk: BulkJobStore
    private val notices = mutableListOf<SyncNotice>()
    private val enqueued = mutableListOf<Pair<String, SyncTrigger>>()
    private val checksNow = mutableListOf<String>()
    private var now = 1_760_000_000_000L
    private val clock = Clock { now }
    private val env = object : SyncEnvironment {
        var free = Long.MAX_VALUE / 4
        override fun freeBytes() = free
        override fun isOnUnmeteredNetwork() = true
        override fun isCharging() = false
    }
    private lateinit var work: Git

    @Before fun setUp() {
        JGitEnv.init()
        val upRoot = tempDir("up")
        upStore = RepoStore(upRoot)
        upSettings = SettingsStore(File(upRoot, "s.json"), FakeCipher).apply { setPassword(upPassword); update { it.copy(authEnabled = false) } }
        upServer = GitServer(upStore, Authenticator({ upSettings.current }, AuthRateLimiter()), AccessLogStore(null), { upSettings.current }, { emptyList() }, { null }, { Long.MAX_VALUE / 4 })
        upPort = (upServer.start(0, "127.0.0.1") as ServerStartResult.Started).port

        val root = tempDir("mirror")
        store = RepoStore(root, clock)
        creds = CredentialStore(File(root, "creds.json"), FakeCipher, clock)
        settings = SettingsStore(File(root, "settings.json"), FakeCipher)
        bulk = BulkJobStore(File(root, "bulk.json"), clock)
        coordinator = SyncCoordinator(store, MirrorEngine(clock), creds, { settings.current }, env, { notices += it }, bulk, clock)
        val scheduler = object : SyncScheduler {
            override fun enqueueUpdate(name: String, trigger: SyncTrigger) { enqueued += name to trigger }
            override fun enqueueCheckNow(name: String) { checksNow += name }
            override fun cancel(name: String) {}
        }
        mirrors = MirrorController(store, MirrorEngine(clock), creds, coordinator, scheduler, { settings.current }, env, bulk, clock)
        ops = RepoOps(store, scheduler, { settings.current }, env, clock = clock)

        work = Commits.initWork()
        Commits.commit(work, "README.md", "v1")
        work.tag().setName("v1").setAnnotated(true).setMessage("v1").call()
        work.branchCreate().setName("feat").call()
        Commits.commit(work, "b.txt", "two")
        pushUpstream("up", "+refs/heads/*:refs/heads/*", "+refs/tags/*:refs/tags/*")
    }

    @After fun tearDown() { upServer.stop(0, 100) }

    private val upUrl get() = "http://127.0.0.1:$upPort/up.git"

    private fun pushUpstream(repo: String, vararg specs: String, delete: List<String> = emptyList()) {
        val push = work.push().setRemote("http://127.0.0.1:$upPort/$repo.git").setForce(true)
            .setCredentialsProvider(UsernamePasswordCredentialsProvider("git", upPassword))
        push.setRefSpecs(specs.map(::RefSpec) + delete.map { RefSpec(":$it") }).call()
    }

    private fun upRefs() = upStore.open("up").use { r -> r.refDatabase.refs.filter { !it.isSymbolic }.associate { it.name to it.objectId.name } }
    private fun myRefs(name: String = "up") = store.open(name).use { r -> r.refDatabase.refs.filter { !it.isSymbolic }.associate { it.name to it.objectId.name } }

    private fun addAndFetch(): RepoMeta {
        val p = mirrors.preview(upUrl)
        assertEquals("up", p.suggestedName)
        val meta = mirrors.add(upUrl, p.suggestedName!!)
        assertEquals(listOf("up" to SyncTrigger.INITIAL), enqueued)
        val ev = runBlocking { coordinator.update(meta.name, SyncTrigger.INITIAL) }
        assertEquals(SyncResult.SUCCESS, ev.result)
        return store.require("up")
    }

    @Test fun initialMirrorCopiesAllRefsAndHead() {
        val meta = addAndFetch()
        assertEquals(upRefs(), myRefs())
        assertEquals("main", meta.defaultBranch)
        assertTrue(meta.mirror!!.initialFetchDone)
        assertEquals(OriginStatus.OK, meta.mirror.originStatus)
        assertEquals(2, store.events("up").first().newCommitCount)
        // F-06 AC8
        assertNotNull(mirrors.preview("$upUrl/").existing)
    }

    @Test fun forcePushDeleteAndTagMoveArePreserved() {
        addAndFetch()
        val oldMain = myRefs()["refs/heads/main"]!!
        val oldFeat = myRefs()["refs/heads/feat"]!!
        val oldTag = myRefs()["refs/tags/v1"]!!
        // upstream rewrites history: reset main to an unrelated commit, deletes feat, moves v1
        val other = Commits.initWork()
        Commits.commit(other, "new.txt", "rewritten")
        work = other
        work.tag().setName("v1").setAnnotated(true).setMessage("moved").setForceUpdate(true).call()
        pushUpstream("up", "+refs/heads/main:refs/heads/main", "+refs/tags/v1:refs/tags/v1", delete = listOf("refs/heads/feat"))

        val check = runBlocking { coordinator.check("up", SyncTrigger.AUTO) }
        assertEquals(3, check.changed)
        assertTrue(check.shouldAutoUpdate)
        assertEquals(3, store.require("up").mirror!!.pendingChanges)

        now += 60_000
        val ev = runBlocking { coordinator.update("up", SyncTrigger.AUTO) }
        assertEquals(3, ev.archivedRefCount)
        val stamp = MirrorEngine.archiveStamp(now)
        val refs = myRefs()
        assertEquals(oldMain, refs["refs/archive/$stamp/heads/main"])
        assertEquals(oldFeat, refs["refs/archive/$stamp/heads/feat"])
        assertEquals(oldTag, refs["refs/archive/$stamp/tags/v1"])
        // AC2: normal refs follow upstream
        assertEquals(upRefs(), refs.filterKeys { !it.startsWith("refs/archive/") })
        assertEquals(3, store.require("up").archivedRefCount)
        assertEquals(0, store.require("up").mirror!!.pendingChanges)
        assertTrue(notices.any { it.kind == NoticeKind.ARCHIVED && it.count == 3 })
        // old objects still readable
        store.open("up").use { assertTrue(it.objectDatabase.has(org.eclipse.jgit.lib.ObjectId.fromString(oldMain))) }

        // AC5: archive refs are advertised to clients (git clone --mirror gets them)
        assumeTrue(GitCli.available)
        val srv = GitServer(store, Authenticator({ settings.current.copy(authEnabled = false) }, AuthRateLimiter()), AccessLogStore(null),
            { settings.current.copy(authEnabled = false) }, { emptyList() }, { null }, { Long.MAX_VALUE / 4 })
        val port = (srv.start(0, "127.0.0.1") as ServerStartResult.Started).port
        try {
            val out = GitCli.ok(tempDir(), "ls-remote", "http://127.0.0.1:$port/up.git")
            assertTrue(out, out.contains("refs/archive/$stamp/heads/main"))
        } finally { srv.stop(0, 100) }
    }

    @Test fun fastForwardDoesNotArchive() {
        addAndFetch()
        Commits.commit(work, "c.txt", "three")
        pushUpstream("up", "refs/heads/main:refs/heads/main")
        val ev = runBlocking { coordinator.update("up", SyncTrigger.MANUAL) }
        assertEquals(0, ev.archivedRefCount)
        assertEquals(1, ev.newCommitCount)
        assertEquals(1, ev.refChanges.size)
        val again = runBlocking { coordinator.update("up", SyncTrigger.MANUAL) }
        assertEquals(SyncResult.NO_CHANGE, again.result)
    }

    @Test fun originDeletedKeepsDataAndRecovers() {
        addAndFetch()
        val before = myRefs()
        runBlocking { RepoOps(upStore, object : SyncScheduler { override fun enqueueUpdate(name: String, trigger: SyncTrigger) {}; override fun enqueueCheckNow(name: String) {}; override fun cancel(name: String) {} }, { upSettings.current }, env).delete("up") }
        val ev = runBlocking { coordinator.update("up", SyncTrigger.AUTO) }
        assertEquals(SyncResult.FAILED, ev.result)
        assertEquals(ErrorCategory.ORIGIN_GONE, ev.errorCategory)
        val m = store.require("up").mirror!!
        assertEquals(OriginStatus.GONE, m.originStatus)
        assertEquals(before, myRefs()) // F-11 AC3: never emptied
        assertTrue(Badges.of(store.require("up"), settings.current, now, null).contains(Badge.ORIGIN_GONE))
        assertEquals(listOf(NoticeKind.ORIGIN_GONE), notices.map { it.kind })
        // repeated failure: no new notification, backoff grows; auto check skipped during backoff
        runBlocking { coordinator.update("up", SyncTrigger.MANUAL) }
        assertEquals(1, notices.size)
        assertEquals(2, store.require("up").mirror!!.consecutiveFailures)
        assertNull(runBlocking { coordinator.check("up", SyncTrigger.AUTO) }.event)
        // upstream comes back
        pushUpstream("up", "+refs/heads/*:refs/heads/*", "+refs/tags/*:refs/tags/*")
        val ok = runBlocking { coordinator.update("up", SyncTrigger.MANUAL) }
        assertTrue(ok.result == SyncResult.SUCCESS || ok.result == SyncResult.NO_CHANGE)
        assertEquals(NoticeKind.RECOVERED, notices.last().kind)
        assertEquals(OriginStatus.OK, store.require("up").mirror!!.originStatus)
    }

    @Test fun privateOriginNeedsCredentialAndTokenNeverLeaks() {
        upSettings.update { it.copy(authEnabled = true) }
        // AC3: probe says token required
        assertEquals(ProbeResult.AuthRequired, mirrors.probe(upUrl))
        val meta = mirrors.add(upUrl, "up")
        val ev = runBlocking { coordinator.update(meta.name, SyncTrigger.INITIAL) }
        assertEquals(ErrorCategory.AUTH_REQUIRED, ev.errorCategory)
        assertEquals(OriginStatus.AUTH_REQUIRED, store.require("up").mirror!!.originStatus)
        assertEquals(NoticeKind.AUTH_REQUIRED, notices.last().kind)
        // wrong token
        val bad = creds.add("127.0.0.1", "wrong-token-123", "git")
        mirrors.setCredential("up", bad.id)
        val ev2 = runBlocking { coordinator.update("up", SyncTrigger.MANUAL) }
        assertEquals(ErrorCategory.AUTH_REQUIRED, ev2.errorCategory)
        assertFalse(ev2.errorDetail!!.contains("wrong-token-123"))
        assertTrue(store.events("up").none { (it.errorDetail ?: "").contains("wrong-token-123") })
        // right token (F-07 AC1 default = first credential for host when none chosen)
        creds.delete(bad.id)
        creds.add("127.0.0.1", upPassword, "git")
        mirrors.setCredential("up", null)
        assertTrue(mirrors.probe(upUrl) is ProbeResult.Reachable)
        val ok = runBlocking { coordinator.update("up", SyncTrigger.MANUAL) }
        assertEquals(SyncResult.SUCCESS, ok.result)
        assertEquals(1, creds.usageCount(creds.items.value.first().id, store.repos.value))
        assertTrue(creds.items.value.first().encryptedToken.startsWith("enc:"))
        assertEquals("upst", creds.items.value.first().tokenPrefix)
    }

    @Test fun storageFloorBlocksAutoAndNewMirrors() {
        addAndFetch()
        env.free = 10
        val ev = runBlocking { coordinator.update("up", SyncTrigger.AUTO) }
        assertEquals(SyncResult.WAITING, ev.result)
        assertEquals(WaitingReason.STORAGE, store.require("up").mirror!!.waitingReason)
        try { mirrors.add("https://github.com/a/b", "b"); error("expected") } catch (e: RepoException) { assertEquals(RepoException.Code.STORAGE_LOW, e.code) }
        // manual update is not subject to the floor
        assertEquals(SyncResult.NO_CHANGE, runBlocking { coordinator.update("up", SyncTrigger.MANUAL) }.result)
    }

    @Test fun delayedBadge() {
        addAndFetch()
        val m = store.require("up")
        assertFalse(Badge.DELAYED in Badges.of(m, settings.current, now + 17 * 3_600_000L, null))
        assertTrue(Badge.DELAYED in Badges.of(m, settings.current, now + 19 * 3_600_000L, null))
    }

    @Test fun multiLineAddCreatesBulkJob() {
        val job = mirrors.addMany("$upUrl\nnot a url\nhttps://github.com/foo/up\n")
        assertEquals(2, job.items.size) // prose lines are ignored when the text contains URLs (share-sheet text)
        assertEquals("foo-up", job.items[1].repoName) // name collision → owner-repo
        assertEquals(BulkItemStatus.FAILED, mirrors.addMany("not a url").items.single().status)
        runBlocking { coordinator.update("up", SyncTrigger.BULK) }
        assertEquals(BulkItemStatus.DONE, bulk.get(job.id)!!.items[0].status)
    }

    @Test fun exportsBundleZipAndBackup() {
        addAndFetch()
        val ex = Exporter(store, "test")
        val dir = tempDir("exp")
        val bundle = Exporter.toFileAtomically(File(dir, "up.bundle")) { ex.writeBundle("up", it) }
        assumeTrue(GitCli.available)
        val clone = File(dir, "restored")
        GitCli.ok(dir, "clone", bundle.absolutePath, clone.absolutePath)
        assertEquals("v1", File(clone, "README.md").readText().trim())
        val zipBytes = ByteArrayOutputStream().also { ex.writeSourceZip("up", "feat", it) }.toByteArray()
        val names = ZipInputStream(zipBytes.inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertEquals(listOf("up-feat/README.md"), names)
        val full = ByteArrayOutputStream().also { ex.writeFullBackup(listOf("up"), it) }.toByteArray()
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(full.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes() } }
        assertEquals(setOf("up.bundle", "manifest.json"), entries.keys)
        val manifest = CoreJson.decodeFromString(BackupManifest.serializer(), String(entries["manifest.json"]!!))
        assertEquals(upRefs(), manifest.repos.single().refs)
        assertFalse(String(entries["manifest.json"]!!).contains("passwordHash"))
        // failed export leaves nothing behind (AC5)
        runCatching { Exporter.toFileAtomically(File(dir, "bad.bundle")) { error("boom") } }
        assertFalse(File(dir, "bad.bundle").exists() || File(dir, "bad.bundle.part").exists())
    }

    @Test fun forkCopyConvertAndDelete() {
        addAndFetch()
        val copy = runBlocking { ops.fork("up", ForkMode.COPY, "up-class") }
        assertEquals(RepoType.LOCAL, copy.type)
        assertEquals(upUrl, copy.forkedFromUrl)
        assertEquals(myRefs("up"), myRefs("up-class"))
        assertEquals(RepoType.MIRROR, store.require("up").type) // mirror untouched
        assertTrue(store.events("up-class").isEmpty())

        val converted = runBlocking { ops.fork("up", ForkMode.CONVERT) }
        assertEquals(RepoType.LOCAL, converted.type); assertNull(converted.mirror)
        assertTrue(store.events("up").isNotEmpty()) // history kept

        runBlocking { ops.setDefaultBranch("up-class", "feat") }
        assertEquals("feat", store.require("up-class").defaultBranch)

        runBlocking { ops.delete("up-class") }
        assertNull(store.get("up-class"))
        assertFalse(File(store.reposDir, "up-class.git").exists())
    }

    @Test fun storeReloadsFromDisk() {
        addAndFetch()
        val again = RepoStore(store.reposDir.parentFile)
        assertEquals(store.require("up"), again.require("UP")) // case-insensitive lookup
    }

    @Test fun headFollowsRemote() {
        addAndFetch()
        upStore.open("up").use { it.updateRef(Constants.HEAD).link("refs/heads/feat") }
        runBlocking { coordinator.update("up", SyncTrigger.MANUAL) }
        assertEquals("feat", store.require("up").defaultBranch)
        assertNotNull(URIish(upUrl))
    }

    // CR-2: review refs are skipped by default, can be turned on per repo, and turning them off deletes them without archiving.
    @Test fun pullRefsFollowSettingAndOverride() {
        pushUpstream("up", "refs/heads/feat:refs/pull/1/head")
        val probe = mirrors.probe(upUrl) as ProbeResult.Reachable
        assertEquals(1, probe.pullRefCount)
        addAndFetch()
        assertTrue(myRefs().keys.none { it.startsWith("refs/pull/") })
        assertEquals(0, runBlocking { coordinator.check("up", SyncTrigger.MANUAL) }.changed)

        mirrors.setIncludePullRefs("up", true)
        runBlocking { coordinator.update("up", SyncTrigger.MANUAL) }
        assertEquals(upRefs()["refs/pull/1/head"], myRefs()["refs/pull/1/head"])

        mirrors.setIncludePullRefs("up", null) // back to the global default (off)
        val ev = runBlocking { coordinator.update("up", SyncTrigger.MANUAL) }
        assertTrue(myRefs().keys.none { it.startsWith("refs/pull/") })
        assertEquals(0, ev.archivedRefCount)

        settings.update { it.copy(mirrorPullRefs = true) }
        runBlocking { coordinator.update("up", SyncTrigger.MANUAL) }
        assertTrue("refs/pull/1/head" in myRefs())
    }

    // CR-3: newest first, and the flow re-emits after a new event.
    @Test fun eventsFlowEmitsNewestFirst() = runBlocking {
        addAndFetch()
        val flow = store.eventsFlow("up")
        val got = async(Dispatchers.Default) { withTimeout(5_000) { flow.take(2).toList() } }
        delay(300)
        Commits.commit(work, "c.txt", "three")
        pushUpstream("up", "+refs/heads/*:refs/heads/*")
        coordinator.update("up", SyncTrigger.MANUAL)
        val (before, after) = got.await()
        assertEquals(before.size + 1, after.size)
        assertEquals(after.maxOf { it.startedAt }, after.first().startedAt)
        assertEquals(after, store.events("up"))
    }

    // CR-4: checkNow only schedules a check; a MANUAL check reports changes but never asks for an update.
    @Test fun checkNowIsCheckOnly() {
        addAndFetch()
        mirrors.checkNow("up")
        assertEquals(listOf("up"), checksNow)
        Commits.commit(work, "c.txt", "three")
        pushUpstream("up", "+refs/heads/*:refs/heads/*")
        val d = runBlocking { coordinator.check("up", SyncTrigger.MANUAL) }
        assertEquals(1, d.changed)
        assertFalse(d.shouldAutoUpdate)
        assertTrue(Badge.UPDATE_AVAILABLE in Badges.of(store.require("up"), settings.current, System.currentTimeMillis(), null))
        ops.createEmpty("plain")
        assertEquals(RepoException.Code.NOT_A_MIRROR, runCatching { mirrors.checkNow("plain") }.exceptionOrNull().let { (it as RepoException).code })
    }

    // QA-03: an already mirrored URL is a distinct error kind (UI offers "go to repo"); add() throws ALREADY_MIRRORED.
    // QA-09: catalog descriptions are kept on bulk-added mirrors.
    @Test fun alreadyMirroredKindAndCatalogDescription() {
        assertNull(mirrors.preview(upUrl).errorKind)
        assertEquals(AddPreviewError.UNSUPPORTED_URL, mirrors.preview("not a url").errorKind)
        val job = mirrors.addFromCatalog(BulkSourceKind.GITHUB_USER, "octo", "127.0.0.1",
            listOf(CatalogRepo("up", "octo/up", "  Upstream demo  ", upUrl, null, false, false, false)), null)
        assertEquals(1, job.items.size)
        assertEquals("Upstream demo", store.require("up").description)
        val again = mirrors.preview(upUrl + "/")
        assertEquals(AddPreviewError.ALREADY_MIRRORED, again.errorKind)
        assertEquals("up", again.existing?.name)
        assertEquals(RepoException.Code.ALREADY_MIRRORED, (runCatching { mirrors.add(upUrl, "up2") }.exceptionOrNull() as RepoException).code)
    }
}
