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

/** F-03 AC6: >10 failures from one IP within 1 minute blocks it for 5 minutes. */
class AuthRateLimiter(private val clock: Clock = SystemClock, private val maxFailures: Int = 10, private val windowMs: Long = 60_000, private val blockMs: Long = 300_000) {
    private val failures = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val blockedUntil = ConcurrentHashMap<String, Long>()

    fun isBlocked(ip: String): Boolean {
        val until = blockedUntil[ip] ?: return false
        if (clock.now() < until) return true
        blockedUntil.remove(ip); return false
    }

    /** Returns true if this failure caused a block. */
    fun recordFailure(ip: String): Boolean {
        val now = clock.now()
        val q = failures.computeIfAbsent(ip) { ArrayDeque() }
        synchronized(q) {
            q.addLast(now)
            while (q.isNotEmpty() && q.first() < now - windowMs) q.removeFirst()
            if (q.size > maxFailures) { blockedUntil[ip] = now + blockMs; q.clear(); return true }
        }
        return false
    }

    fun recordSuccess(ip: String) { failures.remove(ip) }
}

enum class AuthDecision { OK, ANONYMOUS, CHALLENGE, FAILED, BLOCKED }

/**
 * F-03 policy. Verified credentials are memoized by SHA-256 of the header so PBKDF2 runs once per password,
 * not once per HTTP request (a clone is 2-3 requests, the web UI many more).
 */
class Authenticator(private val settings: () -> Settings, private val limiter: AuthRateLimiter) {
    @Volatile private var cache: Pair<String, String>? = null // (passwordHash, sha256(header)) of last success

    fun decide(authorization: String?, clientIp: String, isPush: Boolean, viaNgrok: Boolean): AuthDecision {
        val s = settings()
        if (limiter.isBlocked(clientIp)) return AuthDecision.BLOCKED
        if (!s.effectiveAuthEnabled) return AuthDecision.ANONYMOUS
        if (authorization == null) {
            return if (!isPush && !viaNgrok && s.effectiveAnonymousRead) AuthDecision.ANONYMOUS else AuthDecision.CHALLENGE
        }
        if (verify(authorization, s)) { limiter.recordSuccess(clientIp); return AuthDecision.OK }
        return if (limiter.recordFailure(clientIp)) AuthDecision.BLOCKED else AuthDecision.FAILED
    }

    private fun verify(header: String, s: Settings): Boolean {
        if (!header.startsWith("Basic ", ignoreCase = true)) return false
        val digest = sha256(header)
        val cacheKey = s.passwordHash + "|" + s.username
        cache?.let { (h, d) -> if (h == cacheKey && MessageDigest.isEqual(d.toByteArray(), digest.toByteArray())) return true }
        val decoded = runCatching { String(Base64.getDecoder().decode(header.substring(6).trim())) }.getOrNull() ?: return false
        val idx = decoded.indexOf(':')
        if (idx < 0) return false
        val user = decoded.substring(0, idx); val pw = decoded.substring(idx + 1)
        val ok = user == s.username && PasswordHasher.verify(pw, s.passwordHash)
        if (ok) cache = cacheKey to digest
        return ok
    }

    private fun sha256(s: String) = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
}
