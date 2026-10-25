package com.ouor.mobilegit.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.util.UUID

/** F-07: per-host tokens, encrypted with [SecretCipher] (Android Keystore). Never exported (F-19 AC6). */
class CredentialStore(private val file: File, private val cipher: SecretCipher, private val clock: Clock = SystemClock) {
    /** ARC-01: credentials.json was unreadable at startup; the original is preserved and the list starts empty. */
    var loadIssue: StoreIssue? = null; private set
    private val _items = MutableStateFlow(
        try { JsonFile.readList(file, RemoteCredential.serializer()) ?: emptyList() } catch (e: CorruptFileException) { loadIssue = StoreIssue.of(e); emptyList() })
    val items: StateFlow<List<RemoteCredential>> = _items.asStateFlow()

    /** ARC-03: read-modify-write under one lock so concurrent add/update/delete never drop a change. */
    @Synchronized private fun mutate(f: (List<RemoteCredential>) -> List<RemoteCredential>) {
        val next = f(_items.value); JsonFile.writeList(file, RemoteCredential.serializer(), next); _items.value = next
    }

    fun add(host: String, token: String, username: String? = null, label: String = ""): RemoteCredential {
        val h = normalizeHost(host)
        require(h.isNotEmpty()) { "호스트가 필요합니다" }
        require(token.isNotBlank()) { "토큰이 필요합니다" }
        val c = RemoteCredential(UUID.randomUUID().toString(), h, username?.trim()?.ifEmpty { null }, cipher.encrypt(token.trim()), token.trim().take(4), label, clock.now())
        mutate { it + c }
        return c
    }

    fun update(id: String, token: String? = null, username: String? = null, label: String? = null) {
        val enc = token?.let { t -> cipher.encrypt(t.trim()) }
        mutate { list -> list.map {
        if (it.id != id) it else it.copy(
            encryptedToken = enc ?: it.encryptedToken,
            tokenPrefix = token?.trim()?.take(4) ?: it.tokenPrefix,
            username = username?.trim()?.ifEmpty { null } ?: it.username,
            label = label ?: it.label,
        ) } }
    }

    /** F-07 AC4: callers show [usageCount] and confirm first. */
    fun delete(id: String) = mutate { l -> l.filterNot { it.id == id } }

    fun get(id: String?) = id?.let { i -> _items.value.firstOrNull { it.id == i } }
    fun forHost(host: String) = _items.value.filter { it.host == normalizeHost(host) }

    fun usageCount(id: String, repos: List<RepoMeta>) = repos.count { m ->
        val mir = m.mirror ?: return@count false
        mir.credentialId == id || (mir.credentialId == null && effective(mir)?.id == id)
    }

    /** F-07 AC1: explicit choice, else the first credential stored for the host. */
    fun effective(mirror: MirrorSource): RemoteCredential? =
        get(mirror.credentialId) ?: hostOf(mirror.remoteUrl)?.let { forHost(it).firstOrNull() }

    fun token(c: RemoteCredential): String = cipher.decrypt(c.encryptedToken)

    fun provider(c: RemoteCredential?): CredentialsProvider? = c?.let { UsernamePasswordCredentialsProvider(it.username ?: defaultUser(it.host), token(it)) }

    /** All decrypted tokens, for redacting error text (F-07 AC5). */
    fun secrets(): List<String> = _items.value.mapNotNull { runCatching { token(it) }.getOrNull() }

    companion object {
        fun normalizeHost(h: String) = h.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/')
        fun hostOf(url: String) = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()

        /** Token-only auth usernames understood by common hosts. */
        fun defaultUser(host: String) = when {
            "gitlab" in host -> "oauth2"
            "bitbucket" in host -> "x-token-auth"
            else -> "x-access-token"
        }
    }
}
