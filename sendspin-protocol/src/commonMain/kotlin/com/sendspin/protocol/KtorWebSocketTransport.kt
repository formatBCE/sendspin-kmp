package com.sendspin.protocol

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import com.sendspin.protocol.ProtocolLog as Timber

/**
 * [SendSpinTransport] over a Ktor client WebSocket. The [httpClient] must have the `WebSockets`
 * plugin installed and a platform engine configured by the host (OkHttp on Android, Darwin on iOS,
 * CIO/Java on the JVM) — the library declares no engine so it stays multiplatform.
 *
 * One instance is single-use per logical connection: [SendSpinClient] creates a fresh transport
 * per (re)connect via its transport factory, so this class does not itself reconnect.
 */
class KtorWebSocketTransport(
    private val httpClient: HttpClient,
    private val url: String,
    /** Extra context (e.g. an IO dispatcher) for the receive loop; defaults to the caller's. */
    receiveContext: CoroutineContext = EmptyCoroutineContext,
) : SendSpinTransport {

    private val scope = CoroutineScope(SupervisorJob() + receiveContext)

    private val _state = MutableStateFlow<TransportState>(TransportState.Connecting)
    override val state: StateFlow<TransportState> = _state

    // One ordered flow for both text and binary — preserves wire order so a stream/start is handled
    // between the correct audio chunks. Binary carries audio, so give generous slack; SUSPEND on
    // overflow rather than DROP — dropping an audio chunk here is worse than brief backpressure.
    private val _frames = MutableSharedFlow<TransportFrame>(extraBufferCapacity = 2048, onBufferOverflow = BufferOverflow.SUSPEND)
    override val frames: SharedFlow<TransportFrame> = _frames.asSharedFlow()

    @Volatile private var session: DefaultClientWebSocketSession? = null

    override suspend fun connect() {
        _state.value = TransportState.Connecting
        Timber.i("ws: connecting to %s", url)
        val s = try {
            httpClient.webSocketSession(urlString = url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "ws: connect FAILED: %s", e.message ?: e.toString())
            _state.value = TransportState.Error(e)
            return
        }
        session = s
        Timber.i("ws: session OPEN")
        _state.value = TransportState.Connected
        scope.launch {
            try {
                for (frame in s.incoming) {
                    when (frame) {
                        is Frame.Text -> _frames.emit(TransportFrame.Text(frame.readText()))
                        is Frame.Binary -> _frames.emit(TransportFrame.Binary(frame.readBytes()))
                        else -> { /* Ping/Pong/Close handled by Ktor */ }
                    }
                }
                // incoming closed without error → clean disconnect
                Timber.i("ws: incoming CLOSED")
                if (_state.value is TransportState.Connected) _state.value = TransportState.Disconnected
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "ws: receive loop FAILED: %s", e.message ?: e.toString())
                _state.value = TransportState.Error(e)
            }
        }
    }

    override fun send(text: String): Boolean =
        session?.outgoing?.trySend(Frame.Text(text))?.isSuccess ?: false

    override fun send(bytes: ByteArray): Boolean =
        session?.outgoing?.trySend(Frame.Binary(true, bytes))?.isSuccess ?: false

    override fun disconnect(code: Int, reason: String?) {
        val s = session ?: return
        scope.launch {
            try {
                s.close(CloseReason(code.toShort(), reason ?: ""))
            } catch (_: Exception) {
                // best-effort close
            }
        }
    }

    override fun close() {
        session = null
        scope.cancel()
    }
}
