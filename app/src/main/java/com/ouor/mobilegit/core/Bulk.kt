package com.ouor.mobilegit.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

/** One repo offered by an account/org/group listing (F-15 AC2). */
@Serializable
data class CatalogRepo(
    val name: String, val fullName: String, val description: String?, val cloneUrl: String,
    val sizeBytes: Long?, val fork: Boolean, val archived: Boolean, val isPrivate: Boolean,
)

class CatalogException(val status: Int, message: String) : IOException(message)

/**
 * GitHub/GitLab REST listing with pagination and rate-limit handling (F-15 AC1, AC7, NFR "레이트리밋 헤더 존중").
 * [apiBaseOverride] lets tests point at a fake server.
 */
class RemoteCatalog(private val clock: Clock = SystemClock, private val apiBaseOverride: String? = null) {

    suspend fun list(
        kind: BulkSourceKind, account: String, host: String = "", token: String? = null,
        onRateLimited: (untilMillis: Long) -> Unit = {},
    ): List<CatalogRepo> = withContext(Dispatchers.IO) {
        val acct = account.trim().trim('/')
        require(acct.isNotEmpty()) { "계정/조직 이름이 필요합니다" }
        when (kind) {
            BulkSourceKind.GITHUB_USER, BulkSourceKind.GITHUB_ORG -> github(kind, acct, host.ifBlank { "github.com" }, token, onRateLimited)
            BulkSourceKind.GITLAB_USER, BulkSourceKind.GITLAB_GROUP -> gitlab(kind, acct, host.ifBlank { "gitlab.com" }, token, onRateLimited)
            BulkSourceKind.URL_LIST -> emptyList()
        }
    }

    /** Marks entries that are already mirrored (shown as "등록됨", not selectable). */
    fun alreadyMirrored(list: List<CatalogRepo>, store: RepoStore): Set<String> =
        list.filter { store.findMirrorByUrl(it.cloneUrl) != null }.map { it.cloneUrl }.toSet()

    private fun base(host: String, gh: Boolean): String = apiBaseOverride ?: run {
        val h = CredentialStore.normalizeHost(host)
        if (gh) (if (h == "github.com") "https://api.github.com" else "https://$h/api/v3") else "https://$h/api/v4"
    }

    private suspend fun github(kind: BulkSourceKind, acct: String, host: String, token: String?, rl: (Long) -> Unit): List<CatalogRepo> {
        val b = base(host, true)
        val auth = token?.let { mapOf("Authorization" to "Bearer $it", "Accept" to "application/vnd.github+json") } ?: mapOf("Accept" to "application/vnd.github+json")
        val self = if (kind == BulkSourceKind.GITHUB_USER && token != null)
            runCatching { get("$b/user", auth, rl).body.jsonObject["login"]?.jsonPrimitive?.contentOrNull }.getOrNull() else null
        val first = when {
            kind == BulkSourceKind.GITHUB_ORG -> "$b/orgs/${enc(acct)}/repos?per_page=100&type=all"
            self.equals(acct, ignoreCase = true) -> "$b/user/repos?per_page=100&affiliation=owner&visibility=all"
            else -> "$b/users/${enc(acct)}/repos?per_page=100&type=owner"
        }
        return paginate(first, auth, rl) { el ->
            val o = el.jsonObject
            CatalogRepo(
                name = o.str("name") ?: return@paginate null, fullName = o.str("full_name") ?: "", description = o.str("description"),
                cloneUrl = o.str("clone_url") ?: return@paginate null, sizeBytes = o["size"]?.jsonPrimitive?.longOrNull?.times(1024),
                fork = o.bool("fork"), archived = o.bool("archived"), isPrivate = o.bool("private"),
            )
        }
    }

    private suspend fun gitlab(kind: BulkSourceKind, acct: String, host: String, token: String?, rl: (Long) -> Unit): List<CatalogRepo> {
        val b = base(host, false)
        val auth = token?.let { mapOf("PRIVATE-TOKEN" to it) } ?: emptyMap()
        val first = if (kind == BulkSourceKind.GITLAB_GROUP) "$b/groups/${enc(acct)}/projects?per_page=100&include_subgroups=true&statistics=true"
        else {
            val users = get("$b/users?username=${enc(acct)}", auth, rl).body.jsonArray
            val id = users.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull ?: throw CatalogException(404, "GitLab 사용자 '$acct'을(를) 찾을 수 없습니다")
            "$b/users/$id/projects?per_page=100&statistics=true"
        }
        return paginate(first, auth, rl) { el ->
            val o = el.jsonObject
            CatalogRepo(
                name = o.str("path") ?: return@paginate null, fullName = o.str("path_with_namespace") ?: "", description = o.str("description"),
                cloneUrl = o.str("http_url_to_repo") ?: return@paginate null,
                sizeBytes = (o["statistics"] as? JsonObject)?.get("repository_size")?.jsonPrimitive?.longOrNull,
                fork = o["forked_from_project"].let { it != null && it !is JsonNull }, archived = o.bool("archived"),
                isPrivate = o.str("visibility") == "private",
            )
        }
    }

    private suspend fun paginate(first: String, headers: Map<String, String>, rl: (Long) -> Unit, map: (JsonElement) -> CatalogRepo?): List<CatalogRepo> {
        val out = mutableListOf<CatalogRepo>()
        var url: String? = first
        var guard = 0
        while (url != null && guard++ < 200) {
            val r = get(url, headers, rl)
            r.body.jsonArray.mapNotNullTo(out, map)
            url = r.next
        }
        return out
    }

    private class Resp(val body: JsonElement, val next: String?)

    private suspend fun get(url: String, headers: Map<String, String>, rl: (Long) -> Unit, attempt: Int = 0): Resp {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 30_000
        headers.forEach(c::setRequestProperty)
        c.setRequestProperty("User-Agent", "mobilegit")
        val code = c.responseCode
        if ((code == 403 || code == 429) && attempt < 5) {
            val until = rateLimitUntil(code, c.getHeaderField("Retry-After"), c.getHeaderField("X-RateLimit-Remaining"),
                c.getHeaderField("X-RateLimit-Reset") ?: c.getHeaderField("RateLimit-Reset"))
            if (until != null) {
                rl(until); delay((until - clock.now()).coerceIn(1_000, 3_600_000)); rl(0)
                return get(url, headers, rl, attempt + 1)
            }
        }
        if (code !in 200..299) {
            val msg = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull()?.take(300)
            throw CatalogException(code, when (code) { 401 -> "인증 실패 (토큰 확인 필요)"; 404 -> "계정/조직을 찾을 수 없습니다"; else -> "원격 API 오류 $code: ${Redact.text(msg)}" })
        }
        val body = CoreJson.parseToJsonElement(c.inputStream.bufferedReader().readText())
        val next = (c.getHeaderField("Link")?.let { link -> Regex("""<([^>]+)>;\s*rel="next"""").find(link)?.groupValues?.get(1) }
            ?: c.getHeaderField("X-Next-Page")?.takeIf { it.isNotBlank() }?.let { p ->
                if (Regex("""[?&]page=\d+""").containsMatchIn(url)) url.replace(Regex("""([?&])page=\d+"""), "$1page=$p") else "$url&page=$p"
            })?.takeIf { sameOrigin(url, it) } // SEC-09: the token header never follows a link to another host or to http
        return Resp(body, next)
    }

    /** Returns epoch millis to wait until, or null when the response is not a rate-limit response. */
    fun rateLimitUntil(code: Int, retryAfter: String?, remaining: String?, reset: String?): Long? {
        retryAfter?.trim()?.toLongOrNull()?.let { return clock.now() + it * 1000 }
        if (code == 429 || remaining == "0") {
            val r = reset?.trim()?.toLongOrNull() ?: return clock.now() + 60_000
            // GitHub/GitLab send epoch seconds; small values are "seconds from now".
            return if (r > 1_000_000_000L) r * 1000 else clock.now() + r * 1000
        }
        return null
    }

    private fun sameOrigin(a: String, b: String): Boolean = runCatching {
        val x = URI(a); val y = URI(b)
        x.scheme.equals(y.scheme, true) && x.host.equals(y.host, true) && x.port == y.port
    }.getOrDefault(false)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun JsonObject.str(k: String) = (this[k] as? JsonElement)?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
    private fun JsonObject.bool(k: String) = (this[k] as? JsonElement)?.takeIf { it !is JsonNull }?.jsonPrimitive?.booleanOrNull ?: false
}

/** F-15 / F-06 AC7 progress (A-05). Persisted so progress survives app restarts. */
class BulkJobStore(private val file: File, private val clock: Clock = SystemClock) {
    var loadIssue: StoreIssue? = null; private set
    private val _jobs = MutableStateFlow(
        try { JsonFile.readList(file, BulkJob.serializer()) ?: emptyList() } catch (e: CorruptFileException) { loadIssue = StoreIssue.of(e); emptyList() })
    val jobs: StateFlow<List<BulkJob>> = _jobs.asStateFlow()

    /** ARC-03: every change is a read-modify-write under this lock (worker threads and catalog coroutines race). */
    @Synchronized private fun mutate(f: (List<BulkJob>) -> List<BulkJob>) {
        val l = f(_jobs.value).takeLast(20); if (l == _jobs.value) return; JsonFile.writeList(file, BulkJob.serializer(), l); _jobs.value = l
    }

    fun get(id: String) = _jobs.value.firstOrNull { it.id == id }

    fun create(source: BulkSourceKind, account: String, host: String, items: List<BulkItem>): BulkJob {
        val job = BulkJob(UUID.randomUUID().toString(), source, account, host, clock.now(), items)
        mutate { it + job }
        return job
    }

    /** Updates the item for [repoName] in [jobId] (or in the newest job containing it). */
    fun onRepoStatus(repoName: String, status: BulkItemStatus, error: String?, jobId: String? = null) = mutate { list ->
        val idx = if (jobId != null) list.indexOfFirst { it.id == jobId }
            else list.indexOfLast { j -> j.items.any { it.repoName.equals(repoName, true) } }
        if (idx < 0) return@mutate list
        val job = list[idx]
        val updated = job.copy(items = job.items.map { if (it.repoName.equals(repoName, true)) it.copy(status = status, error = error) else it })
        list.toMutableList().also { it[idx] = updated }
    }

    fun setRateLimited(jobId: String, until: Long?) = mutate { l -> l.map { if (it.id == jobId) it.copy(rateLimitedUntil = until) else it } }
}
