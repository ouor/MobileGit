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
    /** CR-6: default on for new installs (user review round 1). Saved settings keep their stored value. */
    val startOnBoot: Boolean = true,
    val ngrokEnabled: Boolean = false,
    val ngrokAuthtokenEnc: String? = null,
    val ngrokWarningAccepted: Boolean = false,
    /** 0 = "확인 안 함": no periodic check, so no automatic update either. */
    val checkIntervalHours: Int = 6,
    /** No longer shown or read: a check that finds changes always updates. Kept so older settings.json still loads. */
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
    /** R2-02 AC4: the "서버를 꺼도 미러 확인·업데이트는 계속돼요" hint was dismissed. */
    val serverOffHintDismissed: Boolean = false,
    /** R2GIT-04: when the periodic check job last ran ([RepoQuery.checksStalled]). */
    val lastPeriodicCheckAt: Long? = null,
    /** R2SEC-02: boot count and app version of the last process start; a change means "rebooted or updated". */
    val lastStartBootCount: Int? = null,
    val lastStartVersionCode: Int? = null,
) {
    val hasNgrokToken get() = ngrokAuthtokenEnc != null
    /**
     * R2-06 AC4: the user's choice applies on every route, ngrok included (the round-1 rule that ngrok silently forced
     * auth on, F-03 AC7, is gone). Kept as properties for callers; they equal the stored values now.
     */
    val effectiveAuthEnabled get() = authEnabled
    /** "push만": reads without a password (local networks and ngrok, see GitServer), pushes need one. */
    val effectiveAnonymousRead get() = anonymousReadOnLan && authEnabled
    /** R2-06 AC3: ngrok is on and "비밀번호 요구" is "push만" or "끄기"; the UI shows the danger banner. */
    val exposedWithoutPassword get() = ngrokEnabled && (!authEnabled || anonymousReadOnLan)
    /** R2ARC-02: "비밀번호 요구" as one setting: 0 = 읽기·쓰기 (always), 1 = push만, 2 = 끄기. */
    val authLevel: Int get() = when { !authEnabled -> 2; anonymousReadOnLan -> 1; else -> 0 }
    fun withAuthLevel(level: Int) = copy(authEnabled = level != 2, anonymousReadOnLan = level == 1)
    /** R2ARC-02: going from these settings to [next] opens the phone to the internet without a password (R2-06 warning). */
    fun becomesExposed(next: Settings) = next.exposedWithoutPassword && !exposedWithoutPassword
    val checkIntervalMillis get() = checkIntervalHours * 3_600_000L
    val checksOff get() = checkIntervalHours == 0
}

class SettingsException(message: String) : IllegalArgumentException(message)

class SettingsStore(private val file: File, private val cipher: SecretCipher) {
    /**
     * ARC-01: set when settings.json existed but could not be read. The file was preserved ([StoreIssue.preservedAs])
     * and the app started like a first run (server off, new password shown in onboarding) instead of failing.
     */
    var loadIssue: StoreIssue? = null; private set
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val current: Settings get() = _state.value

    private fun load(): Settings {
        try { JsonFile.read(file, Settings.serializer())?.let { return it } }
        catch (e: CorruptFileException) { loadIssue = StoreIssue.of(e) }
        // First run (F-03 AC2): username "git" + random 16-char password, shown once in onboarding.
        val pw = generatePassword()
        val s = Settings(passwordHash = PasswordHasher.hash(pw), initialPasswordEnc = cipher.encrypt(pw))
        JsonFile.write(file, Settings.serializer(), s)
        return s
    }

    /**
     * Applies [transform] and returns at once; the file is written on a background thread (AND-08: callers include
     * Compose click handlers). Writes are coalesced and keep their order; [flush] waits for the latest one.
     */
    @Synchronized
    fun update(transform: (Settings) -> Settings): Settings {
        val next = transform(_state.value)
        _state.value = next
        pending.set(next)
        writer.execute(::persist)
        return next
    }

    private val pending = java.util.concurrent.atomic.AtomicReference<Settings?>(null)
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "settings-write").apply { isDaemon = true } }

    private fun persist() {
        val s = pending.getAndSet(null) ?: return
        try { JsonFile.write(file, Settings.serializer(), s) } catch (e: Exception) { pending.compareAndSet(null, s) } // retried by the next update
    }

    /** Waits until every [update] so far is on disk. */
    fun flush() { writer.submit(::persist).get() }

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
        if (h !in listOf(0, 1, 6, 12, 24)) throw SettingsException("확인 주기는 1, 6, 12, 24시간 또는 확인 안 함 중 하나입니다")
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
