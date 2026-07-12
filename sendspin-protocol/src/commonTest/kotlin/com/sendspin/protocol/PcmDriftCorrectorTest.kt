package com.sendspin.protocol

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcmDriftCorrectorTest {

    // Mono ramp 0..N-1 — easy to reason about interpolation and monotonicity.
    private fun ramp(frames: Int): ShortArray = ShortArray(frames) { it.toShort() }

    @Test
    fun zero_drift_passes_audio_through_at_roughly_same_length() {
        val corrector = PcmDriftCorrector(channelCount = 1)
        val pcm = ramp(1000)
        val out = corrector.correct(pcm, driftMicros = 0L, blockDurationMicros = 20_000L)

        // The last input frame has no successor to interpolate toward, so output is one
        // frame short of input at step == 1 — otherwise length is preserved.
        assertEquals(pcm.size - 1, out.size)
        // With step == 1 and phase starting at 0, interpolation is exact (frac == 0).
        assertEquals(pcm[0], out[0])
        assertEquals(pcm[500], out[500])
    }

    @Test
    fun positive_drift_drops_samples_to_speed_up() {
        val corrector = PcmDriftCorrector(channelCount = 1, maxCorrectionPpm = 50_000.0)
        val pcm = ramp(1000)
        // Drift equal to 5% of the block duration — clamped to maxCorrectionPpm (5%).
        val out = corrector.correct(pcm, driftMicros = 1_000L, blockDurationMicros = 20_000L)

        assertTrue(out.size < pcm.size, "expected output shorter than input but was ${out.size} vs ${pcm.size}")
    }

    @Test
    fun negative_drift_inserts_samples_to_slow_down() {
        val corrector = PcmDriftCorrector(channelCount = 1, maxCorrectionPpm = 50_000.0)
        val pcm = ramp(1000)
        val out = corrector.correct(pcm, driftMicros = -1_000L, blockDurationMicros = 20_000L)

        assertTrue(out.size > pcm.size, "expected output longer than input but was ${out.size} vs ${pcm.size}")
    }

    @Test
    fun correction_is_clamped_to_maxCorrectionPpm_regardless_of_drift_magnitude() {
        val small = PcmDriftCorrector(channelCount = 1, maxCorrectionPpm = 2_000.0)
        val pcm = ramp(10_000)

        val outHuge = small.correct(pcm, driftMicros = 1_000_000L, blockDurationMicros = 20_000L)
        val outModerate = PcmDriftCorrector(channelCount = 1, maxCorrectionPpm = 2_000.0)
            .correct(pcm, driftMicros = 100L, blockDurationMicros = 20_000L) // 0.5% > 0.2% cap

        assertEquals(outModerate.size, outHuge.size)
    }

    @Test
    fun interleaved_stereo_channels_remain_aligned() {
        val corrector = PcmDriftCorrector(channelCount = 2, maxCorrectionPpm = 50_000.0)
        val frames = 500
        val pcm = ShortArray(frames * 2) { it.toShort() }

        val out = corrector.correct(pcm, driftMicros = 800L, blockDurationMicros = 20_000L)

        assertEquals(0, out.size % 2)
        for (i in out.indices step 2) {
            assertTrue(abs((out[i + 1] - out[i]) - 1) <= 1) // interpolation may round to ±1
        }
    }

    @Test
    fun phase_carries_over_continuously_across_blocks() {
        val corrector = PcmDriftCorrector(channelCount = 1, maxCorrectionPpm = 50_000.0)
        val first = corrector.correct(ramp(500), driftMicros = 1_000L, blockDurationMicros = 20_000L)
        val second = corrector.correct(ramp(500), driftMicros = 1_000L, blockDurationMicros = 20_000L)

        assertTrue(first.isNotEmpty())
        assertTrue(second.isNotEmpty())
    }

    @Test
    fun reset_clears_carried_over_phase() {
        val corrector = PcmDriftCorrector(channelCount = 1)
        corrector.correct(ramp(500), driftMicros = 500L, blockDurationMicros = 20_000L)
        corrector.reset()
        val out = corrector.correct(ramp(500), driftMicros = 0L, blockDurationMicros = 20_000L)

        assertEquals(0.toShort(), out[0])
    }

    @Test
    fun short_blocks_are_passed_through_unchanged() {
        val corrector = PcmDriftCorrector(channelCount = 1)
        val pcm = shortArrayOf(42)
        assertEquals(pcm, corrector.correct(pcm, driftMicros = 1000L, blockDurationMicros = 20_000L))
    }
}
