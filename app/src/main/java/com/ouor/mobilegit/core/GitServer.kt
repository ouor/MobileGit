package com.ouor.mobilegit.core

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.condition
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.application.serverConfig
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.pack.PackConfig
import org.eclipse.jgit.transport.PacketLineOut
import org.eclipse.jgit.transport.PostReceiveHook
import org.eclipse.jgit.transport.ReceiveCommand
import org.eclipse.jgit.transport.ReceivePack
import org.eclipse.jgit.transport.RefAdvertiser.PacketLineOutRefAdvertiser
import org.eclipse.jgit.transport.UploadPack
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.BindException
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/** Static web UI files (F-18). Android: `assets/web/`. */
fun interface WebAssets { fun open(path: String): InputStream? }

sealed interface ServerStartResult {
    data class Started(val port: Int, val ngrokPort: Int) : ServerStartResult
    data class PortInUse(val port: Int) : ServerStartResult
    data class Failed(val message: String) : ServerStartResult
}

@Serializable data class ApiError(val code: String, val message: String)
@Serializable data class ApiErrorBody(val error: ApiError)
@Serializable data class ServerInfoDto(val app: String, val version: String, val baseUrl: String, val authRequired: Boolean, val anonymousRead: Boolean)

/**
 * Smart HTTP git server (protocol v0/v2) + read-only JSON API + static web UI on one port (F-01, F-03, F-08, F-13, F-18, F-23).
 *
 * The smart HTTP glue mirrors JGit's own `org.eclipse.jgit.http.server` servlets (UploadPackServlet/ReceivePackServlet,
 * EDL-1.0) on top of Ktor CIO instead of a servlet container.
 * A second connector on 127.0.0.1:<random> is reserved for the ngrok forwarder so ngrok traffic is identifiable
 * (F-03 AC5/AC7, F-23 route = NGROK) even though both arrive from loopback.
 */
class GitServer(
    private val store: RepoStore,
    private val auth: Authenticator,
    private val accessLog: AccessLogStore,
    private val settings: () -> Settings,
    private val addresses: () -> List<ServerAddress>,
    private val assets: WebAssets,
    private val freeBytes: () -> Long,
    private val appVersion: String = "dev",
    private val onPushed: (repo: String, created: Boolean) -> Unit = { _, _ -> },
    private val maxConcurrentPacks: Int = 4,
    private val queueWaitMillis: Long = 300_000,
) {
    @Volatile private var server: EmbeddedServer<*, *>? = null
    @Volatile var ngrokPort: Int = -1; private set
    @Volatile var port: Int = -1; private set
    private val packSlots = Semaphore(maxConcurrentPacks)
    private val activeByRepo = ConcurrentHashMap<String, MutableSet<Job>>()

    val isRunning get() = server != null

    @Synchronized
    fun start(port: Int, host: String = "0.0.0.0"): ServerStartResult {
        if (server != null) return ServerStartResult.Started(this.port, ngrokPort)
        // QA-01: CIO binds inside its accept coroutine, so a BindException there is an *uncaught* coroutine exception
        // (fatal on Android). The handler keeps it inside; start() sees the failure through resolvedConnectors().
        val bindFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val guard = CoroutineExceptionHandler { _, e -> bindFailure.compareAndSet(null, e) }
        val s = embeddedServer(CIO, serverConfig {
            parentCoroutineContext = guard
            module { webModule() }
        }, configure = {
            connector { this.host = host; this.port = port }
            connector { this.host = "127.0.0.1"; this.port = 0 }
            connectionIdleTimeoutSeconds = 120
        })
        return try {
            s.start(wait = false)
            val ports = runBlocking { s.engine.resolvedConnectors() }.map { it.port }
            this.port = ports[0]; ngrokPort = ports[1]
            server = s
            ServerStartResult.Started(this.port, ngrokPort)
        } catch (e: Throwable) {
            runCatching { s.stop(0, 0) }
            val cause = bindFailure.get() ?: e
            if (generateSequence(cause) { it.cause }.any { it is BindException }) ServerStartResult.PortInUse(port)
            else ServerStartResult.Failed(cause.message ?: cause.javaClass.simpleName)
        }
    }

    private fun io.ktor.server.application.Application.webModule() {
            // WR-4: gzip for the web UI and JSON only. Git traffic (packs are already zlib-compressed and git
            // clients stream them) and raw downloads (exact bytes, Content-Length) stay uncompressed.
            install(Compression) {
                gzip {
                    minimumSize(1024)
                    condition { isCompressible(request.path()) }
                }
            }
            routing {
            get("/api/v1/{apiPath...}") { api(call) }
            get("/{repo}/info/refs") { infoRefs(call) }
            post("/{repo}/git-upload-pack") { uploadPack(call) }
            post("/{repo}/git-receive-pack") { receivePack(call) }
            post("/{repo}/info/lfs/objects/batch") { lfsBatch(call) }
            post("/{repo}/info/lfs/locks/verify") { lfsNotFound(call) }
            get("/{staticPath...}") { static(call) }
        }
    }

    /** F-01 AC2: stop accepting, wait up to 10 s for in-flight transfers. */
    @Synchronized
    fun stop(graceMillis: Long = 1_000, timeoutMillis: Long = 10_000) {
        val s = server ?: return
        server = null
        s.stop(graceMillis, timeoutMillis)
        accessLog.flush()
    }

    /** F-21 AC3: cut in-flight transfers of a repo being deleted. */
    fun disconnectRepo(name: String) { activeByRepo.remove(name.lowercase())?.forEach { it.cancel() } }

    // ---------------- request context ----------------

    private inner class Ctx(val call: ApplicationCall) {
        val viaNgrok = call.request.local.localPort == ngrokPort
        val clientIp: String = if (viaNgrok) call.request.header("X-Forwarded-For")?.substringBefore(',')?.trim() ?: call.request.local.remoteAddress
            else call.request.local.remoteAddress
        val route = NetClassifier.route(call.request.local.localAddress, viaNgrok, addresses())
        var authResult = AuthResult.ANONYMOUS

        /** Returns false (and has responded) if the request must stop here. */
        suspend fun authorize(isPush: Boolean, repo: String?, action: AccessAction): Boolean {
            val d = auth.decide(call.request.header(HttpHeaders.Authorization), clientIp, isPush, viaNgrok)
            authResult = when (d) { AuthDecision.OK -> AuthResult.OK; AuthDecision.ANONYMOUS -> AuthResult.ANONYMOUS; AuthDecision.BLOCKED -> AuthResult.BLOCKED; else -> AuthResult.FAILED }
            when (d) {
                AuthDecision.OK, AuthDecision.ANONYMOUS -> return true
                AuthDecision.BLOCKED -> { log(repo, action, 429); call.respondText("Too many failed logins. Try again later.\n", status = HttpStatusCode.TooManyRequests); return false }
                AuthDecision.CHALLENGE, AuthDecision.FAILED -> {
                    if (d == AuthDecision.FAILED) log(repo, action, 401) // a bare challenge is a normal protocol step, not logged
                    call.response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"mobilegit\", charset=\"UTF-8\"")
                    call.respondText("Authentication required\n", status = HttpStatusCode.Unauthorized)
                    return false
                }
            }
        }

        fun log(repo: String?, action: AccessAction, status: Int) =
            accessLog.add(AccessLogEntry(System.currentTimeMillis(), clientIp, route, repo, action, authResult, status))

        fun baseUrl(): String {
            val proto = if (viaNgrok) "https" else (call.request.header("X-Forwarded-Proto") ?: "http")
            val host = call.request.header(HttpHeaders.Host) ?: "localhost:$port"
            return "$proto://$host"
        }
    }

    private suspend fun <T> tracked(repo: String, block: suspend () -> T): T {
        val job = currentCoroutineContext().job
        val set = activeByRepo.computeIfAbsent(repo.lowercase()) { ConcurrentHashMap.newKeySet() }
        set += job
        try { return block() } finally { set -= job }
    }

    private fun invalidNameMessage(call: ApplicationCall): String {
        val raw = call.parameters["repo"].orEmpty()
        return "Invalid repository name '$raw': ${RepoNames.validate(RepoNames.fromPathSegment(raw)) ?: "알 수 없는 이유"}\n"
    }

    private fun repoName(call: ApplicationCall): String? = call.parameters["repo"]?.let(RepoNames::fromPathSegment)?.takeIf(RepoNames::isValid)

    private fun body(call: ApplicationCall, raw: InputStream): InputStream =
        when (call.request.header(HttpHeaders.ContentEncoding)?.lowercase()) {
            "gzip", "x-gzip" -> GZIPInputStream(raw)
            else -> raw
        }

    private fun Repository.uploadPack(call: ApplicationCall) = UploadPack(this).apply {
        setBiDirectionalPipe(false)
        call.request.header("Git-Protocol")?.let { setExtraParameters(it.split(':')) }
        setPackConfig(PackConfig(this@uploadPack).apply { threads = 1 })
    }

    // ---------------- Git LFS (not supported, F-24 / QA-05) ----------------

    /**
     * QA-05: LFS objects are out of scope (PRD 9장, F-24 "경고만"). Without this endpoint git-lfs reports a confusing
     * "Repository or object not found". The batch API's error body `message` is printed by git-lfs as-is, so the user
     * learns why and how to push only the git objects (pointer files). Not silently accepting uploads keeps the
     * "don't lose files quietly" rule: the user opts in with GIT_LFS_SKIP_PUSH=1.
     */
    private suspend fun lfsBatch(call: ApplicationCall) {
        val ctx = Ctx(call)
        val name = repoName(call)
        if (!ctx.authorize(false, name, AccessAction.READ)) return
        val upload = withContext(Dispatchers.IO) { runCatching { call.receiveStream().readNBytes(64 * 1024).toString(Charsets.UTF_8) }.getOrDefault("") }
            .contains(Regex(""""operation"\s*:\s*"upload""""))
        val msg = if (upload)
            "mobilegit은 Git LFS 파일을 저장하지 않습니다 (LFS 미지원). LFS 파일 없이 커밋만 올리려면 GIT_LFS_SKIP_PUSH=1 로 push 하세요 " +
                "(bash: GIT_LFS_SKIP_PUSH=1 git push ..., PowerShell: \$env:GIT_LFS_SKIP_PUSH=1). 휴대폰에는 LFS 포인터 파일만 남습니다."
        else
            "mobilegit에는 LFS 파일 본문이 없습니다 (LFS 미지원). 포인터 파일만 받으려면 GIT_LFS_SKIP_SMUDGE=1 로 clone/pull 하세요."
        ctx.log(name, if (upload) AccessAction.PUSH else AccessAction.READ, 422)
        call.respondText(CoreJson.encodeToString(LfsError.serializer(), LfsError(msg)),
            ContentType.parse("application/vnd.git-lfs+json"), HttpStatusCode.UnprocessableEntity)
    }

    /** Locking API: 404 makes git-lfs treat locking as unsupported and carry on. */
    private suspend fun lfsNotFound(call: ApplicationCall) =
        call.respondText("""{"message":"Git LFS locking is not supported"}""", ContentType.parse("application/vnd.git-lfs+json"), HttpStatusCode.NotFound)

    @Serializable private data class LfsError(val message: String, val documentation_url: String? = null)

    // ---------------- git smart HTTP ----------------

    private suspend fun infoRefs(call: ApplicationCall) {
        val ctx = Ctx(call)
        val service = call.request.queryParameters["service"]
        val name = repoName(call)
        if (service != "git-upload-pack" && service != "git-receive-pack") {
            call.respondText("Only smart HTTP is supported (git 1.6.6+).\n", status = HttpStatusCode.Forbidden); return
        }
        val isPush = service == "git-receive-pack"
        val action = if (isPush) AccessAction.PUSH else AccessAction.READ
        if (!ctx.authorize(isPush, name, action)) return
        if (name == null) { // QA-08: git prints a text/plain error body as "remote: ...", so say why
            ctx.log(null, action, 400); call.respondText(invalidNameMessage(call), status = HttpStatusCode.BadRequest); return
        }
        val meta = store.get(name)
        call.response.header(HttpHeaders.CacheControl, "no-cache, max-age=0, must-revalidate")
        if (!isPush) {
            if (meta == null) { ctx.log(name, action, 404); call.respondText("Repository not found\n", status = HttpStatusCode.NotFound); return }
            withContext(Dispatchers.IO) {
                store.open(name).use { repo ->
                    val up = repo.uploadPack(call)
                    call.respondOutputStream(ContentType.parse("application/x-$service-advertisement")) {
                        val buf = BufferedOutputStream(this)
                        try { up.sendAdvertisedRefs(PacketLineOutRefAdvertiser(PacketLineOut(buf)), service) } finally { up.revWalk.close() }
                        buf.flush()
                    }
                }
            }
            ctx.log(name, action, 200)
            return
        }
        // push
        if (meta?.type == RepoType.MIRROR) {
            // F-08 AC1: git prints "fatal: remote error: <msg>" for an ERR packet in the advertisement.
            call.respondOutputStream(ContentType.parse("application/x-git-receive-pack-advertisement")) {
                val pck = PacketLineOut(this)
                pck.writeString("# service=git-receive-pack\n"); pck.end()
                pck.writeString("ERR ${mirrorMessage(meta)}\n")
                flush()
            }
            ctx.log(name, action, 403); return
        }
        withContext(Dispatchers.IO) {
            // F-13: a missing repo is advertised as empty; it is created only when the pack actually arrives.
            val repo: Repository = if (meta == null) InMemoryRepository(DfsRepositoryDescription(name)) else store.open(name)
            repo.use {
                val rp = ReceivePack(it)
                call.respondOutputStream(ContentType.parse("application/x-git-receive-pack-advertisement")) {
                    val buf = BufferedOutputStream(this)
                    val pck = PacketLineOut(buf)
                    pck.writeString("# service=git-receive-pack\n"); pck.end()
                    try { rp.sendAdvertisedRefs(PacketLineOutRefAdvertiser(pck)) } finally { rp.revWalk.close() }
                    buf.flush()
                }
            }
        }
        ctx.log(name, action, 200)
    }

    private fun mirrorMessage(meta: RepoMeta) =
        "This repository is a read-only mirror of ${Redact.text(meta.mirror?.remoteUrl)}. Fork it in the mobilegit app to push."

    private suspend fun withPackSlot(call: ApplicationCall, block: suspend () -> Unit) {
        val got = withTimeoutOrNull(queueWaitMillis) { packSlots.acquire(); true } ?: false
        if (!got) {
            call.response.header(HttpHeaders.RetryAfter, "30")
            call.respondText("Server busy, try again shortly\n", status = HttpStatusCode.ServiceUnavailable); return
        }
        try { block() } finally { packSlots.release() }
    }

    private suspend fun uploadPack(call: ApplicationCall) {
        val ctx = Ctx(call)
        val name = repoName(call)
        if (!ctx.authorize(false, name, AccessAction.READ)) return
        if (name == null || !store.exists(name)) { ctx.log(name, AccessAction.READ, 404); call.respondText("Repository not found\n", status = HttpStatusCode.NotFound); return }
        if (call.request.header(HttpHeaders.ContentType) != "application/x-git-upload-pack-request") {
            call.respondText("Unsupported media type\n", status = HttpStatusCode.UnsupportedMediaType); return
        }
        withPackSlot(call) {
            tracked(name) {
                withContext(Dispatchers.IO) {
                    store.open(name).use { repo ->
                        val up = repo.uploadPack(call)
                        val input = body(call, call.receiveStream())
                        call.respondOutputStream(ContentType.parse("application/x-git-upload-pack-result")) {
                            up.use { u -> u.uploadWithExceptionPropagation(input, this, null) }
                        }
                    }
                }
            }
        }
        ctx.log(name, AccessAction.READ, 200)
    }

    private suspend fun receivePack(call: ApplicationCall) {
        val ctx = Ctx(call)
        val name = repoName(call)
        if (!ctx.authorize(true, name, AccessAction.PUSH)) return
        if (name == null) {
            ctx.log(null, AccessAction.PUSH, 400)
            call.respondText(invalidNameMessage(call), status = HttpStatusCode.BadRequest); return
        }
        store.get(name)?.takeIf { it.type == RepoType.MIRROR }?.let {
            ctx.log(name, AccessAction.PUSH, 403); call.respondText(mirrorMessage(it) + "\n", status = HttpStatusCode.Forbidden); return
        }
        if (call.request.header(HttpHeaders.ContentType) != "application/x-git-receive-pack-request") {
            call.respondText("Unsupported media type\n", status = HttpStatusCode.UnsupportedMediaType); return
        }
        var status = 200
        var created = false
        withPackSlot(call) {
            tracked(name) {
                withContext(Dispatchers.IO) {
                    store.withRepoLock(name) {
                        if (!store.exists(name)) { store.create(name, RepoType.LOCAL, CreatedBy.PUSH); created = true }
                        val canonical = store.require(name).name
                        store.open(canonical).use { repo ->
                            val rp = ReceivePack(repo)
                            rp.setBiDirectionalPipe(false)
                            rp.isAtomic = true
                            // F-17 AC3 / NFR-저장 공간: refuse packs that do not fit (keeps a 32 MB margin).
                            rp.setMaxPackSizeLimit((freeBytes() - 32L * 1024 * 1024).coerceAtLeast(1))
                            rp.postReceiveHook = PostReceiveHook { r, cmds -> if (created) chooseHead(r.repository, cmds) }
                            val input = body(call, call.receiveStream())
                            call.respondOutputStream(ContentType.parse("application/x-git-receive-pack-result")) {
                                try { rp.receive(input, this, null) } catch (e: Exception) { status = 500 }
                            }
                        }
                        if (created && store.open(canonical).use { it.refDatabase.refs.isEmpty() }) {
                            store.deleteData(canonical); created = false; status = if (status == 200) 400 else status
                        } else {
                            store.update(canonical) { it.copy(lastPushAt = System.currentTimeMillis(), lastPushIp = ctx.clientIp) }
                            runCatching { store.refreshDerived(canonical) }
                        }
                    }
                }
            }
        }
        ctx.log(name, AccessAction.PUSH, status)
        if (status == 200) onPushed(name, created)
    }

    /** F-13 AC5: HEAD = main or master if pushed, else the first pushed branch. */
    private fun chooseHead(repo: Repository, cmds: Collection<ReceiveCommand>) {
        val branches = cmds.filter { it.result == ReceiveCommand.Result.OK && it.type != ReceiveCommand.Type.DELETE && it.refName.startsWith(Constants.R_HEADS) }.map { it.refName }
        val pick = branches.firstOrNull { it == "refs/heads/main" } ?: branches.firstOrNull { it == "refs/heads/master" } ?: branches.firstOrNull() ?: return
        repo.updateRef(Constants.HEAD).link(pick)
    }

    // ---------------- JSON API (GET only) ----------------

    private suspend fun <T> json(call: ApplicationCall, ser: KSerializer<T>, value: T, status: HttpStatusCode = HttpStatusCode.OK) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.respondText(CoreJson.encodeToString(ser, value), ContentType.Application.Json.withParameter("charset", "utf-8"), status)
    }

    private suspend fun apiError(call: ApplicationCall, status: Int, code: String, msg: String) =
        json(call, ApiErrorBody.serializer(), ApiErrorBody(ApiError(code, msg)), HttpStatusCode.fromValue(status))

    private suspend fun api(call: ApplicationCall) {
        val ctx = Ctx(call)
        val parts = call.parameters.getAll("apiPath").orEmpty()
        val repoParam = if (parts.firstOrNull() == "repos") parts.getOrNull(1) else null
        if (!ctx.authorize(false, repoParam, AccessAction.WEB)) return
        val q = call.request.queryParameters
        var status = 200
        try {
            when {
                parts == listOf("info") -> json(call, ServerInfoDto.serializer(), ServerInfoDto("mobilegit", appVersion, ctx.baseUrl(), settings().effectiveAuthEnabled, settings().effectiveAnonymousRead))
                parts == listOf("repos") -> json(call, ListSerializer(RepoSummaryDto.serializer()), store.repos.value.map(RepoBrowser::summary))
                repoParam != null -> {
                    val meta = store.get(repoParam) ?: throw BrowseException(404, "REPO_NOT_FOUND", "레포 '$repoParam'이(가) 없습니다")
                    withContext(Dispatchers.IO) {
                        store.open(meta.name).use { repo ->
                            when (parts.getOrNull(2)) {
                                null -> json(call, RepoDetailDto.serializer(), RepoBrowser.detail(repo, meta).copy(cloneUrl = "${ctx.baseUrl()}/${meta.name}.git"))
                                "tree" -> json(call, TreeDto.serializer(), RepoBrowser.tree(repo, q["ref"], q["path"]))
                                "blob" -> json(call, BlobDto.serializer(), RepoBrowser.blob(repo, q["ref"], q["path"]))
                                "readme" -> {
                                    val r = RepoBrowser.readme(repo, q["ref"], q["path"]) ?: throw BrowseException(404, "README_NOT_FOUND", "README가 없습니다")
                                    json(call, ReadmeDto.serializer(), r)
                                }
                                "commits" -> json(call, CommitPageDto.serializer(), RepoBrowser.commits(repo, q["ref"], q["path"], q["page"]?.toIntOrNull() ?: 1, q["perPage"]?.toIntOrNull() ?: 50))
                                "raw" -> {
                                    val size = RepoBrowser.rawSize(repo, q["ref"], q["path"])
                                    val fileName = (q["path"] ?: "file").substringAfterLast('/').replace(Regex("[\"\\\\\\r\\n]"), "")
                                    // QA-14: images (incl. SVG) are served inline with their real type so <img> can show them;
                                    // everything else stays an opaque download. ?download=1 forces a download for any file.
                                    val imageType = rawImageTypes[fileName.substringAfterLast('.', "").lowercase()]
                                    val inline = imageType != null && q["download"] != "1"
                                    call.response.header(HttpHeaders.ContentDisposition, "${if (inline) "inline" else "attachment"}; filename=\"$fileName\"")
                                    call.response.header("X-Content-Type-Options", "nosniff")
                                    // Opened directly (not via <img>), an SVG is a document: sandbox it so embedded scripts never run.
                                    call.response.header("Content-Security-Policy", RAW_CSP)
                                    // WR-2: let Ktor write Content-Length itself; a manual header plus a streamed body sent both CL and chunked.
                                    call.respondOutputStream(if (inline) ContentType.parse(imageType!!) else ContentType.Application.OctetStream, contentLength = size) {
                                        RepoBrowser.raw(repo, q["ref"], q["path"]) { this }
                                    }
                                }
                                else -> throw BrowseException(404, "NOT_FOUND", "알 수 없는 API")
                            }
                        }
                    }
                }
                else -> throw BrowseException(404, "NOT_FOUND", "알 수 없는 API")
            }
        } catch (e: BrowseException) {
            status = e.status; apiError(call, e.status, e.code, e.message ?: "")
        } catch (e: Exception) {
            status = 500; apiError(call, 500, "INTERNAL", Redact.text(e.message) ?: "internal error")
        }
        ctx.log(repoParam, AccessAction.WEB, status)
    }

    // ---------------- static web UI ----------------

    private val types = mapOf(
        "html" to "text/html; charset=utf-8", "js" to "text/javascript; charset=utf-8", "mjs" to "text/javascript; charset=utf-8",
        "css" to "text/css; charset=utf-8", "json" to "application/json", "svg" to "image/svg+xml", "png" to "image/png",
        "ico" to "image/x-icon", "woff2" to "font/woff2", "woff" to "font/woff", "txt" to "text/plain; charset=utf-8",
        "map" to "application/json", "webp" to "image/webp", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
        "avif" to "image/avif",
    )

    /** QA-14: raw files shown inline. No HTML/XML/PDF: those would render as documents from our origin. */
    private val rawImageTypes = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
        "avif" to "image/avif", "bmp" to "image/bmp", "ico" to "image/x-icon", "svg" to "image/svg+xml",
    )

    private suspend fun static(call: ApplicationCall) {
        val ctx = Ctx(call)
        val path = call.request.path().trimStart('/')
        // WR-1: /r/... is the web UI's history-routing space (repo names and file paths may contain dots), always index.html.
        val isSpaRoute = path == "r" || path.startsWith("r/")
        val isAsset = !isSpaRoute && path.substringAfterLast('/').contains('.')
        if (!ctx.authorize(false, null, AccessAction.WEB)) return
        call.response.header("Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "no-referrer")
        if (path.split('/').any { it == ".." }) { call.respondText("Bad path", status = HttpStatusCode.BadRequest); return }
        val bytes = withContext(Dispatchers.IO) {
            (if (path.isEmpty() || isSpaRoute) null else assets.open(path))?.use { it.readBytes() }
        }
        when {
            bytes != null -> {
                // WR-3: Vite puts content hashes in assets/ file names, so they never change under the same URL.
                if (path.startsWith("assets/")) call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
                call.respondBytes(bytes, ContentType.parse(types[path.substringAfterLast('.').lowercase()] ?: "application/octet-stream"))
            }
            isAsset -> call.respondText("Not found", status = HttpStatusCode.NotFound)
            else -> { // SPA fallback: every non-asset path renders index.html (client-side routing).
                val index = withContext(Dispatchers.IO) { assets.open("index.html")?.use { it.readBytes() } } ?: PLACEHOLDER.toByteArray()
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                call.respondBytes(index, ContentType.Text.Html.withParameter("charset", "utf-8"))
                ctx.log(null, AccessAction.WEB, 200)
            }
        }
    }

    companion object {
        /** WR-4: everything except git smart HTTP and raw file downloads. */
        fun isCompressible(path: String): Boolean = when {
            path.startsWith("/api/v1/") -> !path.endsWith("/raw")
            path.endsWith("/info/refs") || path.endsWith("/git-upload-pack") || path.endsWith("/git-receive-pack") -> false
            else -> true
        }

        /** QA-14: raw responses never run scripts or load anything, even when opened as a top-level document. */
        const val RAW_CSP = "default-src 'none'; img-src data:; style-src 'unsafe-inline'; sandbox"

        private const val PLACEHOLDER ="<!doctype html><meta charset=utf-8><title>mobilegit</title><p>mobilegit 서버가 동작 중입니다. 웹 화면 파일(assets/web/index.html)이 아직 없습니다. JSON API: <a href=\"/api/v1/repos\">/api/v1/repos</a></p>"
    }
}
