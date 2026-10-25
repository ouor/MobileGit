package com.ouor.mobilegit.core

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.storage.file.WindowCacheConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader

/**
 * Process-wide JGit setup for Android:
 * - no system/user/XDG git config (there is no `git` binary or $HOME on Android; probing them forks processes),
 * - no JMX (java.lang.management is absent on Android),
 * - small, non-mmap window cache so many concurrent clones stay within the app heap (NFR-성능).
 */
object JGitEnv {
    @Volatile private var done = false

    @Synchronized
    fun init() {
        if (done) return
        SystemReader.setInstance(object : SystemReader.Delegate(SystemReader.getInstance()) {
            override fun openSystemConfig(parent: Config?, fs: FS?) = empty(parent, fs)
            override fun openUserConfig(parent: Config?, fs: FS?) = empty(parent, fs)
            override fun openJGitConfig(parent: Config?, fs: FS?) = empty(parent, fs)
        })
        WindowCacheConfig().apply {
            isPackedGitMMAP = false
            setExposeStatsViaJmx(false)
            packedGitLimit = 32L * 1024 * 1024
            deltaBaseCacheLimit = 16 * 1024 * 1024
            streamFileThreshold = 16 * 1024 * 1024
        }.install()
        // GIT-04: RepoStore shares one Repository per repo through RepositoryCache; idle ones are closed after 10 min.
        org.eclipse.jgit.lib.RepositoryCacheConfig().apply { expireAfter = 10 * 60_000L }.install()
        done = true
    }

    private fun empty(parent: Config?, fs: FS?) = object : FileBasedConfig(parent, null, fs) {
        override fun load() {}
        override fun isOutdated() = false
        override fun save() {}
    }
}
