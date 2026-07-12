package com.sendspin.protocol

import io.mockk.every
import io.mockk.mockk
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioBufferTest {

    private lateinit var clockSync: ClockSync
    private lateinit var buffer: AudioBuffer

    @BeforeTest
    fun setUp() {
        clockSync = mockk()
        // By default, toLocalMicros is identity (no offset)
        every { clockSync.toLocalMicros(any(), any()) } answers { firstArg() }
        buffer = AudioBuffer(clockSync, capacity = 8)
    }

    // ── Basic ordering ────────────────────────────────────────────────────────

    @Test
    fun chunks_are_returned_in_timestamp_order() {
        val now = ClockSync.localMicros()
        buffer.offer(chunk(now + 3_000_000))
        buffer.offer(chunk(now + 1_000_000))
        buffer.offer(chunk(now + 2_000_000))

        val c1 = buffer.poll(now + 1_500_000)
        val c2 = buffer.poll(now + 2_500_000)
        val c3 = buffer.poll(now + 3_500_000)

        assertEquals(now + 1_000_000, assertNotNull(c1).serverTimestampMicros)
        assertEquals(now + 2_000_000, assertNotNull(c2).serverTimestampMicros)
        assertEquals(now + 3_000_000, assertNotNull(c3).serverTimestampMicros)
    }

    @Test
    fun poll_returns_null_when_no_chunk_is_ready_yet() {
        val now = ClockSync.localMicros()
        buffer.offer(chunk(now + 5_000_000)) // 5 s in the future
        assertNull(buffer.poll(now))
    }

    @Test
    fun poll_returns_chunk_when_its_time_has_passed() {
        val now = ClockSync.localMicros()
        buffer.offer(chunk(now - 100_000)) // 100 ms in the past — still within threshold
        assertNotNull(buffer.poll(now))
    }

    // ── Late chunk dropping ───────────────────────────────────────────────────

    @Test
    fun chunks_older_than_1s_are_dropped_on_offer() {
        val now = ClockSync.localMicros()
        buffer.offer(chunk(now - 1_100_000)) // 1.1 s in the past → should be dropped

        assertEquals(0, buffer.size)
        assertEquals(1L, buffer.droppedChunks)
        assertEquals(1L, buffer.lateChunks)
    }

    @Test
    fun chunks_exactly_at_drop_threshold_are_NOT_dropped() {
        val now = ClockSync.localMicros()
        buffer.offer(chunk(now - 900_000)) // 900 ms in the past → within threshold (1 s)
        assertEquals(1, buffer.size)
    }

    // ── Capacity ──────────────────────────────────────────────────────────────

    @Test
    fun buffer_evicts_furthest_future_when_full() {
        val now = ClockSync.localMicros() + 10_000_000L // future
        repeat(9) { i -> buffer.offer(chunk(now + i * 1_000_000L)) }
        // capacity=8, last offer should evict the furthest-future chunk
        assertEquals(8, buffer.size)
        assertEquals(1L, buffer.droppedChunks)
    }

    // ── Flush ─────────────────────────────────────────────────────────────────

    @Test
    fun flush_empties_the_buffer() {
        val now = ClockSync.localMicros() + 5_000_000L
        repeat(4) { buffer.offer(chunk(now + it * 1_000_000L)) }
        assertEquals(4, buffer.size)
        buffer.flush()
        assertEquals(0, buffer.size)
    }

    @Test
    fun poll_returns_null_after_flush() {
        val now = ClockSync.localMicros()
        buffer.offer(chunk(now - 100_000))
        buffer.flush()
        assertNull(buffer.poll(now))
    }

    // ── nextChunkDelayMicros ──────────────────────────────────────────────────

    @Test
    fun nextChunkDelayMicros_returns_null_when_empty() {
        assertNull(buffer.nextChunkDelayMicros())
    }

    @Test
    fun nextChunkDelayMicros_returns_delay_for_future_chunk() {
        val now = ClockSync.localMicros()
        val futureTs = now + 2_000_000L
        buffer.offer(chunk(futureTs))
        val delay = assertNotNull(buffer.nextChunkDelayMicros(now))
        assertTrue(delay in 1_500_000L..2_100_000L, "Expected delay ~2000000 µs but was $delay")
    }

    // ── Underrun ─────────────────────────────────────────────────────────────

    @Test
    fun underrunState_is_false_initially() {
        assertTrue(!buffer.underrunState.value)
    }

    @Test
    fun signalUnderrun_sets_underrunState_to_true() {
        buffer.signalUnderrun()
        assertTrue(buffer.underrunState.value)
    }

    @Test
    fun offering_chunk_after_underrun_clears_underrunState() {
        buffer.signalUnderrun()
        val now = ClockSync.localMicros() + 5_000_000L
        buffer.offer(chunk(now))
        assertTrue(!buffer.underrunState.value)
    }

    // ── Deferred conversion (live schedule recomputation) ────────────────────

    @Test
    fun clock_correction_after_offer_immediately_reshapes_schedule_of_queued_chunks() {
        val now = ClockSync.localMicros()
        // Initially identity: chunk scheduled for now + 2s
        buffer.offer(chunk(now + 2_000_000L))
        assertNull(buffer.poll(now)) // not ready yet under the original estimate

        // Simulate a clock correction: offset jumps by -1.5s, so the same server timestamp
        // now maps to now + 0.5s instead of now + 2s.
        every { clockSync.toLocalMicros(any(), any()) } answers { firstArg<Long>() - 1_500_000L }

        assertNull(buffer.poll(now)) // still not ready: now + 0.5s > now
        assertNotNull(buffer.poll(now + 500_000L))
    }

    // ── Static delay ─────────────────────────────────────────────────────────

    @Test
    fun staticDelayMicros_shifts_scheduled_play_time_earlier() {
        val now = ClockSync.localMicros()
        buffer.staticDelayMicros = 200_000L // 200 ms

        // Server timestamp 300 ms in the future; with 200 ms delay, local time = now+100 ms
        buffer.offer(chunk(now + 300_000L))

        assertNull(buffer.poll(now))               // not ready at now (local time is now+100 ms)
        assertNotNull(buffer.poll(now + 100_000L)) // ready at now+100 ms
    }

    @Test
    fun zero_staticDelayMicros_does_not_shift_playback_time() {
        val now = ClockSync.localMicros()
        buffer.staticDelayMicros = 0L

        buffer.offer(chunk(now + 300_000L))
        assertNull(buffer.poll(now + 100_000L))
        assertNotNull(buffer.poll(now + 300_000L))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun chunk(serverTimestampMicros: Long) =
        AudioChunk(serverTimestampMicros, ByteArray(64) { 0 })
}
