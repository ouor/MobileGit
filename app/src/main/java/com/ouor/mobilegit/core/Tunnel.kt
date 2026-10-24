package com.ouor.mobilegit.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** F-04 AC5 states shown in settings and the "외부 공개 중" banner. */
sealed interface TunnelState {
    data object Off : TunnelState
    data class Connecting(val attempt: Int) : TunnelState
    data class Connected(val publicUrl: String) : TunnelState
    data class Retrying(val atMillis: Long, val reason: String) : TunnelState
    /** authtoken rejected: no automatic retry ("토큰 확인 필요"). */
    data class TokenRejected(val reason: String) : TunnelState
}

class TunnelAuthException(message: String) : Exception(message)

interface TunnelSession {
    val publicUrl: String
    /** Blocks until the tunnel goes away. */
    fun awaitClosed()
    fun close()
}

/** Android: ngrok-java (official ngrok Agent SDK). Tests: a fake. */
fun interface TunnelBackend {
    /** Opens a tunnel forwarding to 127.0.0.1:[localPort]. Throws [TunnelAuthException] for a bad token. */
    fun connect(authtoken: String, localPort: Int): TunnelSession
}

/** Keeps one tunnel up with exponential backoff (max 5 min). */
class TunnelManager(
    private val backend: TunnelBackend,
    private val scope: CoroutineScope,
    private val clock: Clock = SystemClock,
    private val baseDelayMs: Long = 2_000,
    private val maxDelayMs: Long = 300_000,
) {
    private val _state = MutableStateFlow<TunnelState>(TunnelState.Off)
    val state: StateFlow<TunnelState> = _state.asStateFlow()
    private var job: Job? = null
    @Volatile private var session: TunnelSession? = null

    val publicUrl: String? get() = (state.value as? TunnelState.Connected)?.publicUrl

    @Synchronized
    fun start(authtoken: String, localPort: Int) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive) {
                attempt++
                _state.value = TunnelState.Connecting(attempt)
                val reason = try {
                    val s = runInterruptible { backend.connect(authtoken, localPort) }
                    session = s
                    _state.value = TunnelState.Connected(s.publicUrl)
                    attempt = 0
                    runInterruptible { s.awaitClosed() }
                    "연결이 끊어졌습니다"
                } catch (e: TunnelAuthException) {
                    _state.value = TunnelState.TokenRejected(e.message ?: "authtoken 거부"); return@launch
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                } finally {
                    session?.let { runCatching { it.close() } }; session = null
                }
                val wait = backoff(attempt)
                _state.value = TunnelState.Retrying(clock.now() + wait, reason)
                delay(wait)
            }
        }
    }

    fun backoff(attempt: Int): Long = minOf(maxDelayMs, baseDelayMs shl minOf(attempt.coerceAtLeast(1) - 1, 20))

    @Synchronized
    fun stop() {
        job?.cancel(); job = null
        session?.let { runCatching { it.close() } }; session = null
        _state.value = TunnelState.Off
    }
}
