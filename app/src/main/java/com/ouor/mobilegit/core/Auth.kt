package com.ouor.mobilegit.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Salted slow hash (PBKDF2-HMAC-SHA256), format `pbkdf2-sha256$iter$salt$hash` (F-03 AC3). */
object PasswordHasher {
    private const val ITER = 120_000

    fun hash(password: String, iterations: Int = ITER): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val b64 = Base64.getEncoder()
        return "pbkdf2-sha256\$$iterations\$${b64.encodeToString(salt)}\$${b64.encodeToString(derive(password, salt, iterations))}"
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2-sha256") return false
        val dec = Base64.getDecoder()
        val expected = dec.decode(parts[3])
        return MessageDigest.isEqual(expected, derive(password, dec.decode(parts[2]), parts[1].toInt()))
    }

    private fun derive(pw: String, salt: ByteArray, iter: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pw.toCharArray(), salt, iter, 256)).encoded
}

/**
 * F-03 AC6: >10 failures from one client within 1 minute blocks it for 5 minutes.
 * SEC-07: IPv6 clients are keyed by their /64 (one subscriber owns a whole /64), and the maps are pruned so a flood
 * of addresses cannot grow them without bound.
 */
class AuthRateLimiter(internal val clock: Clock = SystemClock, private val maxFailures: Int = 10, private val windowMs: Long = 60_000,
                      private val blockMs: Long = 300_000, private val maxTracked: Int = 4_096) {
    private val failures = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val blockedUntil = ConcurrentHashMap<String, Long>()

    fun isBlocked(ip: String): Boolean {
        val k = key(ip)
        val until = blockedUntil[k] ?: return false
        if (clock.now() < until) return true
        blockedUntil.remove(k); return false
    }

    /** Returns true if this failure caused a block. */
    fun recordFailure(ip: String): Boolean {
        val now = clock.now()
        if (failures.size >= maxTracked) prune(now)
        val k = key(ip)
        val q = failures.computeIfAbsent(k) { ArrayDeque() }
        synchronized(q) {
            q.addLast(now)
            while (q.isNotEmpty() && q.first() < now - windowMs) q.removeFirst()
            if (q.size > maxFailures) { blockedUntil[k] = now + blockMs; q.clear(); return true }
        }
        return false
    }

    fun recordSuccess(ip: String) { failures.remove(key(ip)) }

    internal fun trackedCount() = failures.size + blockedUntil.size

    private fun prune(now: Long) {
        failures.entries.removeIf { (_, q) -> synchronized(q) { q.isEmpty() || q.last() < now - windowMs } }
        blockedUntil.entries.removeIf { it.value <= now }
        if (failures.size >= maxTracked) failures.clear() // still flooded: forget counts, keep active blocks
    }

    private fun key(ip: String): String {
        if (!ip.contains(':')) return ip
        val b = runCatching { java.net.InetAddress.getByName(ip.substringBefore('%').trim('[', ']')).address }.getOrNull() ?: return ip
        if (b.size != 16) return ip
        return (0 until 8).joinToString(":") { "%02x".format(b[it]) } + "::/64"
    }
}

enum class AuthDecision { OK, ANONYMOUS, CHALLENGE, FAILED, BLOCKED }

/**
 * F-03 policy. Verified credentials are memoized by SHA-256 of the header so PBKDF2 runs once per password,
 * not once per HTTP request (a clone is 2-3 requests, the web UI many more).
 *
 * SEC-01: on the ngrok path every client is somewhere on the internet and per-address limits can be dodged by
 * changing addresses, so failures there also count toward one shared limit ([ngrokLimiter], 30 per minute → 1
 * minute block for unknown credentials). The last known-good header keeps working during such a block.
 * SEC-02: at most two PBKDF2 checks run at once; a request that cannot get a turn within 3 s is answered 429.
 */
class Authenticator(
    private val settings: () -> Settings,
    private val limiter: AuthRateLimiter,
    private val ngrokLimiter: AuthRateLimiter = AuthRateLimiter(limiter.clock, maxFailures = 30, blockMs = 60_000),
    private val hashSlots: java.util.concurrent.Semaphore = java.util.concurrent.Semaphore(2),
) {
    @Volatile private var cache: Pair<String, String>? = null // (passwordHash|username, sha256(header)) of last success

    /** [anonymousOk]: the request may use "anonymous read on the same network" (SEC-03: local client and direct Host). */
    fun decide(authorization: String?, clientIp: String, isPush: Boolean, viaNgrok: Boolean, anonymousOk: Boolean = true): AuthDecision {
        val s = settings()
        if (limiter.isBlocked(clientIp)) return AuthDecision.BLOCKED
        if (!s.effectiveAuthEnabled) return AuthDecision.ANONYMOUS
        if (authorization == null) {
            return if (!isPush && anonymousOk && s.effectiveAnonymousRead) AuthDecision.ANONYMOUS else AuthDecision.CHALLENGE
        }
        if (!authorization.startsWith("Basic ", ignoreCase = true)) return fail(clientIp, viaNgrok)
        val digest = sha256(authorization)
        val cacheKey = s.passwordHash + "|" + s.username
        cache?.let { (h, d) -> if (h == cacheKey && MessageDigest.isEqual(d.toByteArray(), digest.toByteArray())) { limiter.recordSuccess(clientIp); return AuthDecision.OK } }
        if (viaNgrok && ngrokLimiter.isBlocked(NGROK_KEY)) return AuthDecision.BLOCKED
        if (!hashSlots.tryAcquire(3, java.util.concurrent.TimeUnit.SECONDS)) return AuthDecision.BLOCKED
        val ok = try { verify(authorization, s) } finally { hashSlots.release() }
        if (ok) { cache = cacheKey to digest; limiter.recordSuccess(clientIp); return AuthDecision.OK }
        return fail(clientIp, viaNgrok)
    }

    private fun fail(clientIp: String, viaNgrok: Boolean): AuthDecision {
        val shared = viaNgrok && ngrokLimiter.recordFailure(NGROK_KEY)
        return if (limiter.recordFailure(clientIp) || shared) AuthDecision.BLOCKED else AuthDecision.FAILED
    }

    private fun verify(header: String, s: Settings): Boolean {
        val decoded = runCatching { String(Base64.getDecoder().decode(header.substring(6).trim())) }.getOrNull() ?: return false
        val idx = decoded.indexOf(':')
        if (idx < 0) return false
        val user = decoded.substring(0, idx); val pw = decoded.substring(idx + 1)
        return user == s.username && PasswordHasher.verify(pw, s.passwordHash)
    }

    private fun sha256(s: String) = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))

    private companion object { const val NGROK_KEY = "ngrok" }
}
