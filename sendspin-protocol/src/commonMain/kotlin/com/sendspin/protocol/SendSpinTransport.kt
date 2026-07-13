package com.sendspin.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A single received frame, carrying its wire type. Control/metadata arrive as [Text] (JSON), and
 * audio/artwork/visualizer as [Binary] (length-prefixed). Both travel through ONE ordered
 * [SendSpinTransport.frames] flow so the client sees them in exact wire order — critical because a
 * `stream/start` (Text) must be handled strictly between the last old-stream and first new-stream
 * audio chunk, or the two streams interleave.
 */
sealed interface TransportFrame {
    data class Text(val text: String) : TransportFrame
    data class Binary(val bytes: ByteArray) : TransportFrame {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Binary) return false
            return bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = bytes.contentHashCode()
    }
}

/** Connection lifecycle of a [SendSpinTransport], as observed by [SendSpinClient]. */
sealed interface TransportState {
    /** A connection attempt is in progress. */
    data object Connecting : TransportState
    /** The transport is open and ready to carry frames. */
    data object Connected : TransportState
    /** The transport reported a clean close (peer or local). */
    data object Disconnected : TransportState
    /** The transport failed; [cause] is the underlying error. */
    data class Error(val cause: Throwable) : TransportState
}

/**
 * Bidirectional message transport for the SendSpin protocol.
 *
 * Abstracts the concrete carrier (a Ktor WebSocket, a WebRTC data channel, …) behind send +
 * receive + lifecycle so [SendSpinClient] is carrier-agnostic. Received frames — control/metadata
 * (JSON text) and audio/artwork/visualizer (length-prefixed binary) — are delivered through ONE
 * ordered [frames] flow that preserves wire order. Implementations MUST NOT split them into
 * separate streams (that reorders control vs audio and interleaves consecutive streams on a skip).
 *
 * Reconnection is intentionally NOT the transport's concern — [SendSpinClient] owns the backoff
 * loop and re-establishes the transport, so implementations report only raw connect/close/error.
 *
 * [send] is non-suspending (enqueue-and-return, mirroring the old OkHttp semantics) so the client's
 * many synchronous send sites need no coroutine; it returns whether the frame was accepted for
 * delivery.
 */
interface SendSpinTransport {
    val state: StateFlow<TransportState>

    /** All received frames in wire order (Text and Binary interleaved as they arrived). */
    val frames: Flow<TransportFrame>

    /** Establish the connection. Suspends until connected; updates [state] to Error and returns on failure. */
    suspend fun connect()

    fun send(text: String): Boolean
    fun send(bytes: ByteArray): Boolean

    /** Begin a graceful close. Non-blocking; the actual close completes asynchronously. */
    fun disconnect(code: Int = 1000, reason: String? = null)

    /** Release all resources (scopes, sessions). The transport is unusable afterwards. */
    fun close()
}
