package com.sendspin.protocol

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals

class VolumeCurveTest {

    private val prefs = ClientPreferences(
        supportedFormats = listOf(AudioFormat("flac", 2, 48000, 16)),
        artworkChannels = emptyList(),
    )

    private fun clientWithCapture(): Pair<SendSpinClient, RecordingAudioPlayer> {
        val player = RecordingAudioPlayer()
        return testClient(preferences = prefs, audioPlayer = player) to player
    }

    @Test
    fun volume_100_yields_gain_1() {
        val (client, player) = clientWithCapture()
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"volume","volume":100}}}""")
        assertEquals(1, player.gains.size)
        assertEquals(1.0f, player.gains[0], 0.001f)
    }

    @Test
    fun volume_0_yields_gain_0() {
        val (client, player) = clientWithCapture()
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"volume","volume":0}}}""")
        assertEquals(1, player.gains.size)
        assertEquals(0.0f, player.gains[0], 0.001f)
    }

    @Test
    fun volume_50_yields_perceptual_curve_gain() {
        val (client, player) = clientWithCapture()
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"volume","volume":50}}}""")
        assertEquals(1, player.gains.size)
        assertEquals((50 / 100.0).pow(1.5).toFloat(), player.gains[0], 0.001f)
    }

    @Test
    fun mute_yields_gain_0_regardless_of_volume() {
        val (client, player) = clientWithCapture()
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"volume","volume":80}}}""")
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"mute","mute":true}}}""")
        assertEquals(2, player.gains.size)
        assertEquals(0.0f, player.gains[1], 0.001f)
    }

    @Test
    fun unmuting_restores_previous_volume_curve_gain() {
        val (client, player) = clientWithCapture()
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"volume","volume":80}}}""")
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"mute","mute":true}}}""")
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"mute","mute":false}}}""")
        assertEquals(3, player.gains.size)
        assertEquals((80 / 100.0).pow(1.5).toFloat(), player.gains[2], 0.001f)
    }

    @Test
    fun out_of_range_volume_is_clamped() {
        val (client, player) = clientWithCapture()
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"volume","volume":150}}}""")
        assertEquals(1, player.gains.size)
        assertEquals(1.0f, player.gains[0], 0.001f)
    }

    @Test
    fun volume_command_missing_volume_field_is_ignored() {
        val (client, player) = clientWithCapture()
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"volume"}}}""")
        assertEquals(0, player.gains.size)
    }

    @Test
    fun mute_command_missing_mute_field_is_ignored() {
        val (client, player) = clientWithCapture()
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"mute"}}}""")
        assertEquals(0, player.gains.size)
    }

    @Test
    fun set_static_delay_command_applies_delay_to_audio_buffer() {
        val (client, player) = clientWithCapture()
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"set_static_delay","static_delay_ms":250}}}""")
        assertEquals(250_000L, client.audioBuffer.staticDelayMicros)
        assertEquals(0, player.gains.size)
    }

    @Test
    fun group_update_volume_does_not_affect_player_gain() {
        val (client, player) = clientWithCapture()
        client.handleTextMessage("""{"type":"group/update","payload":{"volume":30,"muted":false}}""")
        assertEquals(0, player.gains.size)
        assertEquals(30, client.controllerState.value?.volume)
    }
}
