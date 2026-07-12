package com.sendspin.protocol

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClockSyncTest {

    private val clockSync = ClockSync()

    @Test
    fun offset_converges_after_multiple_measurements_with_constant_offset() {
        val trueOffsetMicros = 50_000L // 50 ms server-ahead
        val rttMicros = 4_000L         // 4 ms round-trip

        repeat(20) {
            val t1 = 1_000_000L + it * 100_000L
            val t2 = t1 + trueOffsetMicros + rttMicros / 2
            val t3 = t2 + 100L
            val t4 = t1 + rttMicros + 100L
            clockSync.processMeasurement(t1, t2, t3, t4)
        }

        val estimatedOffset = clockSync.lastOffsetMicros
        assertTrue(
            abs(estimatedOffset - trueOffsetMicros) < 2_000.0,
            "Offset should converge near $trueOffsetMicros µs but was $estimatedOffset",
        )
    }

    @Test
    fun toLocalMicros_subtracts_offset_from_server_timestamp() {
        val trueOffsetMicros = 20_000L
        val rttMicros = 2_000L
        repeat(15) {
            val t1 = 500_000L + it * 50_000L
            val t2 = t1 + trueOffsetMicros + rttMicros / 2
            val t3 = t2 + 50L
            val t4 = t1 + rttMicros + 50L
            clockSync.processMeasurement(t1, t2, t3, t4)
        }

        val serverTimestamp = 2_000_000L
        val localTimestamp = clockSync.toLocalMicros(serverTimestamp)

        val expected = serverTimestamp - trueOffsetMicros
        assertTrue(
            abs(localTimestamp - expected) < 3_000L,
            "Local time should be near $expected but was $localTimestamp",
        )
    }

    @Test
    fun single_measurement_moves_offset_away_from_zero() {
        assertEquals(0L, clockSync.offsetMicros)

        clockSync.processMeasurement(
            t1 = 1_000_000L,
            t2 = 1_010_000L, // +10 ms (server ahead)
            t3 = 1_010_100L,
            t4 = 1_002_100L,
        )

        assertTrue(clockSync.offsetMicros > 0)
    }

    @Test
    fun calculateProgress_returns_base_when_paused() {
        assertEquals(12_345L, ClockSync.calculateProgress(12_345L, 0L, 0, 60_000L, nowMicros = 999_999L))
    }

    @Test
    fun calculateProgress_extrapolates_forward_at_normal_speed() {
        val result = ClockSync.calculateProgress(10_000L, 0L, 1000, 0L, nowMicros = 5_000_000L)
        assertEquals(15_000L, result)
    }

    @Test
    fun calculateProgress_extrapolates_at_1_5x_speed() {
        val result = ClockSync.calculateProgress(0L, 0L, 1500, 0L, nowMicros = 10_000_000L)
        assertEquals(15_000L, result)
    }

    @Test
    fun calculateProgress_clamps_to_track_duration() {
        val result = ClockSync.calculateProgress(55_000L, 0L, 1000, 60_000L, nowMicros = 10_000_000L)
        assertEquals(60_000L, result)
    }

    @Test
    fun calculateProgress_clamps_to_zero_when_result_goes_negative() {
        val result = ClockSync.calculateProgress(1_000L, 5_000_000L, 1000, 0L, nowMicros = 0L)
        assertEquals(0L, result)
    }

    @Test
    fun calculateProgress_does_not_clamp_upper_bound_when_duration_is_zero() {
        val result = ClockSync.calculateProgress(1_000_000L, 0L, 1000, 0L, nowMicros = 0L)
        assertEquals(1_000_000L, result)
    }

    @Test
    fun high_RTT_measurements_are_incorporated_but_with_less_weight() {
        val trueOffset = 5_000L
        val normalRtt = 2_000L
        val highRtt = 100_000L

        repeat(10) {
            val t1 = it * 10_000L
            clockSync.processMeasurement(t1, t1 + trueOffset + normalRtt / 2, t1 + trueOffset + normalRtt / 2 + 10, t1 + normalRtt + 10)
        }
        val baselineOffset = clockSync.lastOffsetMicros

        clockSync.processMeasurement(0, trueOffset + highRtt / 2, trueOffset + highRtt / 2 + 10, highRtt + 10)

        assertTrue(abs(clockSync.lastOffsetMicros - baselineOffset) < 3_000.0)
    }

    @Test
    fun rtt_4ms_converges_as_tightly_as_the_1ms_convergence_test() {
        val trueOffset = 30_000L
        val rtt = 4_000L

        repeat(20) {
            val t1 = 1_000_000L + it * 100_000L
            val t2 = t1 + trueOffset + rtt / 2
            val t3 = t2 + 100L
            val t4 = t1 + rtt + 100L
            clockSync.processMeasurement(t1, t2, t3, t4)
        }

        assertTrue(
            abs(clockSync.lastOffsetMicros - trueOffset) < 2_000.0,
            "Expected convergence within 2ms for RTT=4ms, was ${clockSync.lastOffsetMicros}",
        )
    }

    @Test
    fun low_RTT_outlier_perturbs_estimate_more_than_high_RTT_outlier() {
        // With the first sample seeding the state directly, a strict convergence-from-zero race is
        // no longer meaningful (both filters seed exactly on noiseless input). The real weighting
        // property: once seeded, a low-RTT (trusted) outlier moves the estimate more than an
        // equally-offset high-RTT (distrusted) one.
        val trueOffset = 40_000L

        fun seed(c: ClockSync) {
            val t1 = 1_000_000L; val rtt = 1_000L
            c.processMeasurement(t1, t1 + trueOffset + rtt / 2, t1 + trueOffset + rtt / 2 + 50L, t1 + rtt + 50L)
        }
        fun outlier(c: ClockSync, rtt: Long) {
            val t1 = 1_200_000L; val noise = 10_000L // measured offset is 10 ms too high
            c.processMeasurement(t1, t1 + trueOffset + noise + rtt / 2, t1 + trueOffset + noise + rtt / 2 + 50L, t1 + rtt + 50L)
        }

        val lowRttClock = ClockSync().also { seed(it); outlier(it, rtt = 1_000L) }   // R = (250µs)^2
        val highRttClock = ClockSync().also { seed(it); outlier(it, rtt = 20_000L) } // R = (5000µs)^2 — 400× larger

        val lowMove = abs(lowRttClock.lastOffsetMicros - trueOffset)
        val highMove = abs(highRttClock.lastOffsetMicros - trueOffset)
        assertTrue(
            lowMove > highMove,
            "Low-RTT outlier should perturb the estimate more (low=$lowMove µs, high=$highMove µs)",
        )
    }

    @Test
    fun offset_stays_accurate_and_drift_stays_sane_with_realistic_server_clock_offset() {
        // Regression for the on-device audio-lag bug: real servers timestamp against a monotonic
        // clock ~10^12 µs ahead of the client's process clock, and probes arrive in sparse bursts
        // (~10 s apart). Starting xOffset at 0 made the first innovation the entire ~10^12 offset,
        // which leaked into the drift state (xDrift ≈ several µs/µs) and every later predict()
        // extrapolated that into tens of seconds of offset error — garbled/silent playback.
        val trueOffset = 3_452_369_038_000L // ~3.45e12 µs, matching observed device logs
        val rtt = 4_000L
        val t1Start = 1_000_000_000L        // > any realistic test-process uptime, so first dt > 0
        repeat(20) {
            val t1 = t1Start + it * 10_000_000L // 10 s apart — sparse, like real bursts
            val t2 = t1 + trueOffset + rtt / 2
            val t3 = t2 + 50L
            val t4 = t1 + rtt + 50L
            clockSync.processMeasurement(t1, t2, t3, t4)
        }

        assertTrue(
            abs(clockSync.lastOffsetMicros - trueOffset) < 10_000.0,
            "Offset should stay within 10 ms of $trueOffset but was ${clockSync.lastOffsetMicros}",
        )
        assertTrue(
            abs(clockSync.lastDriftPpm) < 1_000.0,
            "Drift must stay physically plausible (< 1000 ppm) but was ${clockSync.lastDriftPpm} ppm",
        )
    }

    // ── toLocalMicros drift gate ──────────────────────────────────────────────

    @Test
    fun toLocalMicros_result_does_not_vary_with_elapsed_time_when_drift_SNR_is_low() {
        val trueOffset = 30_000L
        repeat(10) { i ->
            val t1 = 1_000_000L + i * 200_000L
            val rtt = 2_000L
            val t2 = t1 + trueOffset + rtt / 2
            val t3 = t2 + 50L
            val t4 = t1 + rtt + 50L
            clockSync.processMeasurement(t1, t2, t3, t4)
        }
        val lastT4 = 1_000_000L + 9 * 200_000L + 2_000L + 50L
        val serverTs = lastT4 + trueOffset
        val atT4 = clockSync.toLocalMicros(serverTs, lastT4)
        val later = clockSync.toLocalMicros(serverTs, lastT4 + 5_000_000L)
        assertEquals(atT4, later, "drift gate should suppress elapsed-time drift when SNR is low (xDrift ≈ 0)")
    }

    @Test
    fun toLocalMicros_extrapolates_drift_forward_once_drift_estimate_converges() {
        val trueOffset = 30_000L
        val trueDrift = 1_000e-6  // 1000 ppm (µs offset per µs elapsed)
        val rtt = 2_000L
        repeat(100) { i ->
            val t1 = 1_000_000L + i * 200_000L
            val offsetAtT1 = trueOffset + (trueDrift * t1).toLong()
            val t2 = t1 + offsetAtT1 + rtt / 2
            val t3 = t2 + 50L
            val t4 = t1 + rtt + 50L
            clockSync.processMeasurement(t1, t2, t3, t4)
        }
        val lastT4 = 1_000_000L + 99 * 200_000L + rtt + 50L
        val serverTs = lastT4 + trueOffset + (trueDrift * lastT4).toLong()
        val elapsed = 1_000_000L  // 1 second
        val atT4 = clockSync.toLocalMicros(serverTs, lastT4)
        val later = clockSync.toLocalMicros(serverTs, lastT4 + elapsed)
        val driftDelta = (atT4 - later).toDouble()
        assertTrue(
            abs(driftDelta - trueDrift * elapsed) < 500.0,
            "Expected drift delta ≈ ${(trueDrift * elapsed).toLong()} µs, got $driftDelta µs",
        )
    }
}
