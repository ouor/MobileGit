package com.ouor.mobilegit.core

import java.net.URI

object RepoNames {
    private val pattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")

    /** PRD 6.1. Returns null when valid, otherwise a user-facing reason. */
    fun validate(name: String): String? = when {
        name.isEmpty() -> "이름이 비어 있습니다"
        name.length > 100 -> "이름은 100자 이하여야 합니다 (지금 ${name.length}자)"
        !name[0].isLetterOrDigit() || name[0].code > 127 -> "이름은 영문이나 숫자로 시작해야 합니다"
        !pattern.matches(name) -> "이름에는 영문, 숫자, '.', '_', '-'만 쓸 수 있습니다 (허용되지 않는 문자: " +
            name.filterNot { it.isLetterOrDigit() && it.code < 128 || it in "._-" }.toSet().joinToString(", ") { if (it == ' ') "공백" else "'$it'" } + ")"
        name.endsWith(".git", ignoreCase = true) -> "이름은 .git으로 끝날 수 없습니다"
        else -> null
    }

    fun isValid(name: String) = validate(name) == null

    /** Strips a trailing ".git" from a URL path segment (F-01 AC5: /<name>.git and /<name> are the same repo). */
    fun fromPathSegment(segment: String): String = segment.removeSuffix(".git")

    /** F-06 AC2: remote repo name, or <owner>-<repo> if taken, then numbered. */
    fun suggest(remote: NormalizedRemote, taken: (String) -> Boolean): String {
        val base = sanitize(remote.repo)
        if (!taken(base)) return base
        val withOwner = sanitize("${remote.owner.substringAfterLast('/')}-${remote.repo}")
        if (!taken(withOwner)) return withOwner
        var i = 2
        while (taken("$withOwner-$i")) i++
        return "$withOwner-$i"
    }

    fun sanitize(raw: String): String {
        var s = raw.removeSuffix(".git").replace(Regex("[^A-Za-z0-9._-]"), "-").trimStart('.', '_', '-')
        if (s.isEmpty()) s = "repo"
        s = s.take(100)
        while (s.endsWith(".git", true)) s = s.dropLast(4).ifEmpty { "repo" }
        return s
    }
}

data class NormalizedRemote(val url: String, val host: String, val owner: String, val repo: String)

/** F-06 AC1: normalizes pasted HTTPS URLs / browser URLs into a clone URL. */
object RemoteUrls {
    private val githubLike = setOf("github.com", "bitbucket.org")
    private val browserMarkers = setOf("tree", "blob", "commits", "commit", "issues", "pulls", "pull", "releases", "tags", "branches",
        "actions", "wiki", "src", "raw", "blame", "compare", "activity", "network", "settings", "-", "merge_requests")

    fun normalize(input: String): NormalizedRemote? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        val uri = runCatching { URI(s) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host?.lowercase() ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        var segments = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        // GitLab browser URLs use "/-/" before tree/blob; others put the marker right after owner/repo.
        val dash = segments.indexOf("-")
        if (dash >= 2) segments = segments.subList(0, dash)
        if (host in githubLike || host.endsWith(".github.com")) {
            if (segments.size < 2) return null
            segments = segments.take(2)
        } else if (segments.size > 2) {
            val markerIdx = segments.indexOfFirst { it in browserMarkers }
            if (markerIdx >= 2) segments = segments.subList(0, markerIdx)
        }
        if (segments.size < 2 && host !in githubLike) {
            // e.g. a self-hosted git server at https://host/repo.git
            if (segments.size == 1 && segments[0].endsWith(".git")) Unit else return null
        }
        val repo = segments.last().removeSuffix(".git")
        if (repo.isEmpty()) return null
        val owner = segments.dropLast(1).joinToString("/")
        val path = (segments.dropLast(1) + "$repo.git").joinToString("/")
        return NormalizedRemote("$scheme://$host$port/$path", host, owner, repo)
    }

    /** F-06 AC6/AC7: pulls every http(s) URL (or one per non-empty line) out of shared/pasted text. */
    fun extractCandidates(text: String): List<String> {
        val urls = Regex("""https?://[^\s<>"']+""").findAll(text).map { it.value.trimEnd('.', ',', ')', ']') }.toList()
        if (urls.isNotEmpty()) return urls.distinct()
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /** Key used to detect "already mirrored" (F-06 AC8). */
    fun identity(url: String): String = url.lowercase().removeSuffix("/").removeSuffix(".git").removePrefix("https://").removePrefix("http://")
}
