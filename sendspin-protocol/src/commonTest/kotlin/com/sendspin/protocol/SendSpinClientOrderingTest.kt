package com.sendspin.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for the "two songs mixed chunk-by-chunk after skip" bug. On a skip the wire order is
 * `…old audio chunk → stream/start → new audio chunk…`; the single frame collector processes these
 * sequentially, and the discontinuity buffer clear in the stream/start handler MUST run
 * synchronously so it lands between the old and new chunks. Driving the handlers directly (as the
 * collector does, in order) proves the old chunk is gone before the new one is offered.
 *
 * Before the fix (async `audioScope.launch { audioPlayer.flush() }`, buffer cleared inside the
 * host AudioPlayer) the flush neither ran synchronously nor cleared the buffer via the library, so
 * the old chunk survived and interleaved with the new one — this test fails on that code path.
 */
class SendSpinClientOrderingTest {

    private fun audioFrame(ts: Long, payload: ByteArray): ByteArray {
        val out = ByteArray(9 + payload.size)
        out[0] = BINARY_TYPE_AUDIO
        for (i in 0 until 8) out[1 + i] = (ts ushr (8 * (7 - i))).toByte() // big-endian int64
        payload.copyInto(out, destinationOffset = 9)
        return out
    }

    @Test
    fun stream_start_synchronously_drops_old_chunk_before_new_one_is_offered() {
        val client = testClient() // fresh: clock offset 0, so toLocalMicros is identity
        val now = ClockSync.localMicros()
        val oldPayload = byteArrayOf(1, 1, 1)
        val newPayload = byteArrayOf(2, 2, 2)

        // Old-stream chunk (scheduled ≈ now, so it's admitted, not dropped as late/far-future).
        client.handleBinaryMessage(audioFrame(now, oldPayload))
        assertEquals(1, client.audioBuffer.size, "old chunk should be buffered before stream/start")

        // stream/start = discontinuity → synchronous buffer flush.
        client.handleTextMessage(
            """{"type":"stream/start","payload":{"player":{"codec":"pcm","sample_rate":48000,"channels":2,"bit_depth":16}}}""",
        )
        assertEquals(0, client.audioBuffer.size, "stream/start must synchronously flush the old chunk")

        // New-stream chunk offered after the flush survives.
        client.handleBinaryMessage(audioFrame(now + 1_000, newPayload))
        assertEquals(1, client.audioBuffer.size, "only the new chunk survives")

        val survivor = client.audioBuffer.poll(now + 2_000)
        assertEquals(newPayload.toList(), survivor?.data?.toList(), "survivor must be the NEW chunk")
    }
}
