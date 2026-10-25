package com.ouor.mobilegit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.mobilegit.app.KeystoreCipher
import com.ouor.mobilegit.app.NgrokBackend
import com.ouor.mobilegit.core.AccessLogStore
import com.ouor.mobilegit.core.AuthRateLimiter
import com.ouor.mobilegit.core.Authenticator
import com.ouor.mobilegit.core.CredentialStore
import com.ouor.mobilegit.core.Exporter
import com.ouor.mobilegit.core.GitServer
import com.ouor.mobilegit.core.JGitEnv
import com.ouor.mobilegit.core.MirrorEngine
import com.ouor.mobilegit.core.MirrorSource
import com.ouor.mobilegit.core.RepoStore
import com.ouor.mobilegit.core.RepoType
import com.ouor.mobilegit.core.CreatedBy
import com.ouor.mobilegit.core.ServerStartResult
import com.ouor.mobilegit.core.SettingsStore
import com.ouor.mobilegit.core.SyncCoordinator
import com.ouor.mobilegit.core.SyncEnvironment
import com.ouor.mobilegit.core.SyncResult
import com.ouor.mobilegit.core.SyncTrigger
import com.ouor.mobilegit.core.TunnelAuthException
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Runs the real stack on ART: Keystore, JGit (server + client + archive fetch), Ktor CIO, ngrok (gomobile). */
@RunWith(AndroidJUnit4::class)
class OnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun tmp() = File(ctx.cacheDir, "t-" + UUID.randomUUID()).apply { mkdirs() }
    private val who = PersonIdent("T", "t@e")

    @Test fun keystoreRoundTrip() {
        val c = KeystoreCipher("mobilegit-test")
        val enc = c.encrypt("ghp_secret_token")
        assertTrue(!enc.contains("ghp_secret_token"))
        assertEquals("ghp_secret_token", c.decrypt(enc))
    }

    @Test fun smartHttpPushCloneAndArchiveOnArt() = runBlocking {
        JGitEnv.init()
        val root = tmp()
        val store = RepoStore(File(root, "srv"))
        val settings = SettingsStore(File(root, "s.json"), KeystoreCipher("mobilegit-test")).apply { setPassword("device-pass") }
        val server = GitServer(store, Authenticator({ settings.current }, AuthRateLimiter()), AccessLogStore(null), { settings.current }, { emptyList() }, { null }, { Long.MAX_VALUE / 4 })
        val port = (server.start(0, "127.0.0.1") as ServerStartResult.Started).port
        val cp = UsernamePasswordCredentialsProvider("git", "device-pass")
        try {
            val work = Git.init().setDirectory(File(root, "work")).setInitialBranch("main").call()
            File(work.repository.workTree, "a.txt").writeText("1")
            work.add().addFilepattern("a.txt").call()
            val first = work.commit().setMessage("1").setAuthor(who).setCommitter(who).setSign(false).call()
            val url = "http://127.0.0.1:$port/dev.git"
            work.push().setRemote(url).setCredentialsProvider(cp).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
            assertEquals(RepoType.LOCAL, store.require("dev").type)

            val clone = Git.cloneRepository().setURI(url).setDirectory(File(root, "clone")).setCredentialsProvider(cp).call()
            assertEquals("1", File(clone.repository.workTree, "a.txt").readText())

            // mirror it locally, then force-push upstream and check the archive ref
            val mStore = RepoStore(File(root, "mirror"))
            val creds = CredentialStore(File(root, "c.json"), KeystoreCipher("mobilegit-test")).apply { add("127.0.0.1", "device-pass", "git") }
            val env = object : SyncEnvironment { override fun freeBytes() = Long.MAX_VALUE / 4; override fun isOnUnmeteredNetwork() = true; override fun isCharging() = true }
            val coord = SyncCoordinator(mStore, MirrorEngine(), creds, { settings.current }, env, { })
            mStore.create("dev", RepoType.MIRROR, CreatedBy.MIRROR_ADD, mirror = MirrorSource(url))
            assertEquals(SyncResult.SUCCESS, coord.update("dev", SyncTrigger.INITIAL).result)

            File(work.repository.workTree, "a.txt").writeText("rewritten")
            work.add().addFilepattern("a.txt").call()
            work.commit().setAmend(true).setMessage("rewritten").setAuthor(who).setCommitter(who).setSign(false).call()
            work.push().setRemote(url).setCredentialsProvider(cp).setForce(true).setRefSpecs(RefSpec("+refs/heads/main:refs/heads/main")).call()
            val ev = coord.update("dev", SyncTrigger.MANUAL)
            assertEquals(1, ev.archivedRefCount)
            val archived = mStore.open("dev").use { r -> r.refDatabase.getRefsByPrefix("refs/archive/").single().objectId.name }
            assertEquals(first.name, archived)

            val out = ByteArrayOutputStream()
            Exporter(mStore, "t").writeBundle("dev", out)
            assertTrue(out.size() > 0)
        } finally { server.stop(0, 100) }
    }

    @Test fun ngrokNativeLoadsAndRejectsBogusToken() {
        try {
            NgrokBackend().connect("bogus${UUID.randomUUID().toString().replace("-", "")}", 1)
            fail("expected rejection")
        } catch (e: TunnelAuthException) {
            // expected: Go library loaded, ngrok service refused the token (ERR_NGROK_105)
            assertTrue(e.message, e.message!!.contains("ERR_NGROK") || e.message!!.contains("authtoken"))
        }
    }
}
