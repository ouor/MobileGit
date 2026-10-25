package com.ouor.mobilegit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.concurrent.thread

/** ARC-18: state files survive restarts, damage, schema drift and crashes without silently resetting user data. */
class StorageTest {
    private fun copies(f: File, tag: String) = f.parentFile.listFiles { x -> x.name.startsWith("${f.name}.$tag-") }.orEmpty().toList()

    @Test fun corruptSettingsArePreservedNotSilentlyReset() {
        val f = File(tempDir(), "settings.json")
        f.writeText("""{"port": 9000, "username": "me", """) // truncated
        val s = SettingsStore(f, FakeCipher)
        assertNotNull(s.loadIssue)
        val kept = copies(f, "corrupt").single()
        assertEquals("""{"port": 9000, "username": "me", """, kept.readText())
        assertEquals(kept.absolutePath, s.loadIssue!!.preservedAs)
        assertFalse(s.current.serverEnabled) // server stays off until the user looks
        assertNotNull(s.initialPassword()) // onboarding shows the new password again
        // a second start with the same bad copy does not pile up more copies
        f.writeText("""{"port": 9000, "username": "me", """); SettingsStore(f, FakeCipher)
        assertEquals(1, copies(f, "corrupt").size)
    }

    @Test fun unknownEnumsAndFieldsFallBackToDefaults() {
        val f = File(tempDir(), "settings.json")
        f.writeText("""{"port": 9001, "futureField": {"x": 1}, "username": "me"}""")
        val s = SettingsStore(f, FakeCipher)
        assertNull(s.loadIssue); assertEquals(9001, s.current.port); assertEquals("me", s.current.username)

        val root = tempDir()
        val store = RepoStore(root)
        store.create("m", RepoType.MIRROR, CreatedBy.MIRROR_ADD, mirror = MirrorSource("https://example.com/a.git", credentialId = "c1"))
        val meta = File(store.reposDir, "m.git/mobilegit/meta.json")
        meta.writeText(meta.readText().replace("\"originStatus\":\"UNKNOWN\"", "\"originStatus\":\"SOMETHING_NEW\"")
            .replace("\"lastError\":null", "\"lastError\":\"NEW_CATEGORY\"").replace("{\"id\"", "{\"added\":[1,2],\"id\""))
        val again = RepoStore(root)
        val m = again.require("m")
        assertEquals(RepoType.MIRROR, m.type)
        assertEquals("https://example.com/a.git", m.mirror!!.remoteUrl)
        assertEquals("c1", m.mirror!!.credentialId)
        assertEquals(OriginStatus.UNKNOWN, m.mirror!!.originStatus)
        assertNull(m.mirror!!.lastError)
    }

    @Test fun unreadableMetaIsReportedNeverAdoptedOrOverwritten() {
        val root = tempDir()
        val store = RepoStore(root)
        store.create("m", RepoType.MIRROR, CreatedBy.MIRROR_ADD, mirror = MirrorSource("https://example.com/a.git"))
        val meta = File(store.reposDir, "m.git/mobilegit/meta.json")
        meta.writeText("{not json")
        val again = RepoStore(root)
        assertNull(again.get("m"))
        assertEquals("m", again.damaged.value.single().name)
        assertEquals("{not json", meta.readText()) // untouched
        try { again.create("m", RepoType.LOCAL, CreatedBy.PUSH); error("must refuse") } catch (e: RepoException) { assertEquals(RepoException.Code.NAME_TAKEN, e.code) }
        assertTrue(File(store.reposDir, "m.git/HEAD").exists())
    }

    @Test fun missingMetaWithHeadIsAdopted() {
        val root = tempDir()
        RepoStore(root).create("p", RepoType.LOCAL, CreatedBy.PUSH)
        File(root, "repos/p.git/mobilegit/meta.json").delete()
        val m = RepoStore(root).require("p")
        assertEquals(RepoType.LOCAL, m.type)
    }

    @Test fun listsSkipBadEntriesKeepOthersAndReadOldFormat() {
        val f = File(tempDir(), "credentials.json")
        // pre-schemaVersion format: a bare array; the second entry lacks required fields
        f.writeText("""[{"id":"a","host":"github.com","encryptedToken":"enc:dA==","tokenPrefix":"t","createdAt":1},{"id":"b"}]""")
        val c = CredentialStore(f, FakeCipher)
        assertNull(c.loadIssue)
        assertEquals(listOf("a"), c.items.value.map { it.id })
        assertEquals(1, copies(f, "partial").size) // the dropped entry is still on disk
        c.add("gitlab.com", "tok2")
        val text = f.readText()
        assertTrue(text, text.startsWith("{\"schemaVersion\":1,\"items\":["))
        assertEquals(2, CredentialStore(f, FakeCipher).items.value.size)
    }

    @Test fun newerSchemaIsCopiedBeforeRewrite() {
        val f = File(tempDir(), "settings.json")
        f.writeText("""{"schemaVersion": 7, "port": 9002}""")
        val s = SettingsStore(f, FakeCipher)
        assertEquals(9002, s.current.port)
        assertEquals(1, copies(f, "v7").size)
        s.setPort(9003); s.flush()
        assertTrue(f.readText().contains("\"schemaVersion\":1"))
    }

    @Test fun startupSweepsLeftovers() {
        val root = tempDir()
        val store = RepoStore(root)
        store.create("r", RepoType.LOCAL, CreatedBy.APP_EMPTY)
        val dir = File(store.reposDir, "r.git")
        val leftovers = listOf(File(dir, "refs/heads/main.lock"), File(dir, "packed-refs.lock"), File(dir, "config.lock"),
            File(dir, "objects/pack/incoming_123.pack"), File(dir, "mobilegit/.events.json.x.tmp"), File(dir, "mobilegit/meta.json.tmp"))
        leftovers.forEach { it.parentFile.mkdirs(); it.writeText("x"); it.setLastModified(System.currentTimeMillis() - 120_000) }
        File(store.reposDir, ".tmp-1234/objects").mkdirs()
        File(store.reposDir, ".trash-1234/objects").mkdirs()
        File(store.reposDir, "orphan.git/mobilegit").mkdirs() // no HEAD, no meta
        kotlinx.coroutines.runBlocking { RepoStore(root).sweepAll() }
        leftovers.forEach { assertFalse(it.path, it.exists()) }
        assertEquals(setOf("r.git"), store.reposDir.list()!!.toSet())
    }

    @Test fun historyOfDeletedRepoIsNotRecreated() {
        val store = RepoStore(tempDir())
        store.create("d", RepoType.MIRROR, CreatedBy.MIRROR_ADD, mirror = MirrorSource("https://example.com/d.git"))
        store.deleteData("d")
        store.appendEvent("d", SyncEvent(1, 0, SyncTrigger.AUTO, SyncKind.CHECK, SyncResult.SUCCESS))
        store.appendPushEvents("d", listOf(PushEvent(1, "refs/heads/main", null, "a".repeat(40), AccessRoute.WIFI, "1.2.3.4")))
        assertFalse(File(store.reposDir, "d.git").exists())
        assertTrue(store.reposDir.list()!!.isEmpty())
    }

    @Test fun concurrentCredentialChangesAreNotLost() {
        val f = File(tempDir(), "credentials.json")
        val c = CredentialStore(f, FakeCipher)
        (1..8).map { i -> thread { repeat(10) { j -> c.add("h$i.example.com", "token-$i-$j") } } }.forEach { it.join() }
        assertEquals(80, c.items.value.size)
        assertEquals(80, CredentialStore(f, FakeCipher).items.value.size)
    }

    @Test fun accessLogClearSurvivesConcurrentFlush() {
        val f = File(tempDir(), "accesslog.json")
        val log = AccessLogStore(f).apply { awaitLoaded() }
        repeat(50) { log.add(AccessLogEntry(it.toLong(), "1.1.1.1", AccessRoute.WIFI, null, AccessAction.WEB, AuthResult.OK, 200)) }
        val t = thread { repeat(20) { log.flush() } }
        log.clear(); t.join(); log.flush()
        assertTrue(AccessLogStore(f).apply { awaitLoaded() }.entries.value.isEmpty())
    }

    @Test fun backgroundLoadAndWriteKeepEverything() {
        val f = File(tempDir(), "accesslog.json")
        AccessLogStore(f).apply { awaitLoaded(); add(AccessLogEntry(1, "a", AccessRoute.WIFI, null, AccessAction.WEB, AuthResult.OK, 200)); flush() }
        val log = AccessLogStore(f)
        log.add(AccessLogEntry(2, "b", AccessRoute.WIFI, null, AccessAction.WEB, AuthResult.OK, 200)) // maybe before the load finished
        log.awaitLoaded(); log.flush()
        assertEquals(listOf(2L, 1L), AccessLogStore(f).apply { awaitLoaded() }.entries.value.map { it.at })

        val sf = File(tempDir(), "settings.json")
        val s = SettingsStore(sf, FakeCipher)
        (1025..1060).forEach { s.setPort(it) } // AND-08: returns at once, written in order in the background
        s.flush()
        assertEquals(1060, SettingsStore(sf, FakeCipher).current.port)
    }
}
