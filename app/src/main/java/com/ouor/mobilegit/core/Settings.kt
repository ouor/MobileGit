package com.ouor.mobilegit.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.security.SecureRandom

/** PRD 6.7. Secrets are stored hashed ([passwordHash]) or encrypted (`*Enc`). */
@Serializable
data class Settings(
    val serverEnabled: Boolean = false,
    val port: Int = 8080,
    val authEnabled: Boolean = true,
    val username: String = "git",
    val passwordHash: String = "",
    val anonymousReadOnLan: Boolean = false,
    val startOnBoot: Boolean = false,
    val ngrokEnabled: Boolean = false,
    val ngrokAuthtokenEnc: String? = null,
    val ngrokWarningAccepted: Boolean = false,
    val checkIntervalHours: Int = 6,
    val autoUpdate: Boolean = true,
    val updateWifiOnly: Boolean = true,
    val updateChargingOnly: Boolean = false,
    val checkWifiOnly: Boolean = false,
    /** CR-2 global default for [MirrorSource.includePullRefs]. Off: heads/tags are what F-11 archives; PR refs dominate size/time. */
    val mirrorPullRefs: Boolean = false,
    val storageFloorBytes: Long = 1L shl 30,
    val notifyOnSuccess: Boolean = false,
    val batteryHintDismissedAt: Long? = null,
    val onboardingCompleted: Boolean = false,
    /** First-run password, kept (encrypted) only until onboarding completes (F-03 AC3). */
    val initialPasswordEnc: String? = null,
    /** Boot count when the server was last switched on; distinguishes process restarts from reboots (F-01 AC7 vs F-22 AC2). */
    val serverBootCount: Int? = null,
) {
    val hasNgrokToken get() = ngrokAuthtokenEnc != null
    /** F-03 AC7: while ngrok is on, "auth off" and "anonymous LAN read" are ignored. */
    val effectiveAuthEnabled get() = authEnabled || ngrokEnabled
    val effectiveAnonymousRead get() = anonymousReadOnLan && !ngrokEnabled && authEnabled
    val checkIntervalMillis get() = checkIntervalHours * 3_600_000L
}

class SettingsException(message: String) : IllegalArgumentException(message)

class SettingsStore(private val file: File, private val cipher: SecretCipher) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val current: Settings get() = _state.value

    private fun load(): Settings {
        JsonFile.read(file, Settings.serializer())?.let { return it }
        // First run (F-03 AC2): username "git" + random 16-char password, shown once in onboarding.
        val pw = generatePassword()
        val s = Settings(passwordHash = PasswordHasher.hash(pw), initialPasswordEnc = cipher.encrypt(pw))
        JsonFile.write(file, Settings.serializer(), s)
        return s
    }

    @Synchronized
    fun update(transform: (Settings) -> Settings): Settings {
        val next = transform(_state.value)
        JsonFile.write(file, Settings.serializer(), next)
        _state.value = next
        return next
    }

    fun setPort(port: Int) {
        if (port !in 1024..65535) throw SettingsException("포트는 1024~65535 사이여야 합니다")
        update { it.copy(port = port) }
    }

    fun setUsername(name: String) {
        if (name.isBlank() || name.contains(':') || name.length > 64) throw SettingsException("사용자 이름이 올바르지 않습니다")
        update { it.copy(username = name.trim()) }
    }

    /** F-03 AC2/AC3: reset only, never reveal. */
    fun setPassword(password: String) {
        if (password.length < 8) throw SettingsException("비밀번호는 8자 이상이어야 합니다")
        update { it.copy(passwordHash = PasswordHasher.hash(password), initialPasswordEnc = null) }
    }

    /** Onboarding only. Returns null after [completeOnboarding]. */
    fun initialPassword(): String? = current.initialPasswordEnc?.let { runCatching { cipher.decrypt(it) }.getOrNull() }

    fun completeOnboarding() = update { it.copy(onboardingCompleted = true, initialPasswordEnc = null) }

    fun setNgrokAuthtoken(token: String?) = update { it.copy(ngrokAuthtokenEnc = token?.trim()?.takeIf { t -> t.isNotEmpty() }?.let(cipher::encrypt)) }
    fun ngrokAuthtoken(): String? = current.ngrokAuthtokenEnc?.let { runCatching { cipher.decrypt(it) }.getOrNull() }

    fun setCheckIntervalHours(h: Int) {
        if (h !in listOf(1, 6, 12, 24)) throw SettingsException("확인 주기는 1, 6, 12, 24시간 중 하나입니다")
        update { it.copy(checkIntervalHours = h) }
    }

    companion object {
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        fun generatePassword(len: Int = 16): String {
            val rnd = SecureRandom()
            return String(CharArray(len) { ALPHABET[rnd.nextInt(ALPHABET.length)] })
        }
    }
}
