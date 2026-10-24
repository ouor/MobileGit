package com.ouor.mobilegit.core

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Base64 "cipher" for JVM tests; production uses Android Keystore AES-GCM. */
object FakeCipher : SecretCipher {
    override fun encrypt(plain: String) = "enc:" + Base64.getEncoder().encodeToString(plain.toByteArray())
    override fun decrypt(cipherText: String) = String(Base64.getDecoder().decode(cipherText.removePrefix("enc:")))
}

fun tempDir(prefix: String = "mg"): File = Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

data class CmdResult(val code: Int, val out: String)

/** Runs the real `git` CLI (installed on the dev PC) with an isolated config. */
object GitCli {
    val available: Boolean by lazy { runCatching { run(File("."), "--version").code == 0 }.getOrDefault(false) }

    fun run(dir: File, vararg args: String, env: Map<String, String> = emptyMap()): CmdResult {
        val home = tempDir("home")
        val pb = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true)
        pb.environment().apply {
            put("HOME", home.absolutePath); put("USERPROFILE", home.absolutePath)
            put("GIT_CONFIG_NOSYSTEM", "1"); put("GIT_TERMINAL_PROMPT", "0"); put("GCM_INTERACTIVE", "never")
            put("GIT_AUTHOR_NAME", "Tester"); put("GIT_AUTHOR_EMAIL", "t@example.com")
            put("GIT_COMMITTER_NAME", "Tester"); put("GIT_COMMITTER_EMAIL", "t@example.com")
            putAll(env)
        }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(120, TimeUnit.SECONDS)
        return CmdResult(p.exitValue(), out)
    }

    fun ok(dir: File, vararg args: String): String {
        val r = run(dir, "-c", "credential.helper=", *args)
        check(r.code == 0) { "git ${args.joinToString(" ")} failed (${r.code}):\n${r.out}" }
        return r.out
    }
}

/** Builds commits with JGit (no CLI needed). */
object Commits {
    private val who = PersonIdent("Tester", "t@example.com")
    fun commit(git: Git, file: String, content: String, msg: String = "edit $file"): String {
        val f = File(git.repository.workTree, file); f.parentFile.mkdirs(); f.writeText(content)
        git.add().addFilepattern(file).call()
        return git.commit().setMessage(msg).setAuthor(who).setCommitter(who).setSign(false).call().name
    }
    fun initWork(dir: File = tempDir("work"), branch: String = "main"): Git = Git.init().setDirectory(dir).setInitialBranch(branch).call()
}
