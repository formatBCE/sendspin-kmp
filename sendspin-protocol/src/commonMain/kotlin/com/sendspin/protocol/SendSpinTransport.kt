package com.sendspin.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

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
 * receive + lifecycle so [SendSpinClient] is carrier-agnostic. Both a text and a binary channel
 * are first-class: control/metadata travel as JSON text frames, audio/artwork/visualizer as
 * length-prefixed binary frames.
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
    val textFrames: Flow<String>
    val binaryFrames: Flow<ByteArray>

    /** Establish the connection. Suspends until connected; updates [state] to Error and returns on failure. */
    suspend fun connect()

    fun send(text: String): Boolean
    fun send(bytes: ByteArray): Boolean

    /** Begin a graceful close. Non-blocking; the actual close completes asynchronously. */
    fun disconnect(code: Int = 1000, reason: String? = null)

    /** Release all resources (scopes, sessions). The transport is unusable afterwards. */
    fun close()
}
