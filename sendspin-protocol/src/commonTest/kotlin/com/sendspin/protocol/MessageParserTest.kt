package com.sendspin.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageParserTest {

    private val parser = MessageParser()

    // ── Text messages ─────────────────────────────────────────────────────────

    @Test
    fun parse_server_hello() {
        val json = """
            {
              "type": "server/hello",
              "payload": {
                "server_id": "abc123",
                "name": "My Server",
                "version": 1,
                "active_roles": ["player@v1", "metadata@v1"]
              }
            }
        """.trimIndent()

        val hello = assertIs<ServerHello>(parser.parseText(json))
        assertEquals("My Server", hello.name)
        assertEquals(listOf("player@v1", "metadata@v1"), hello.activeRoles)
    }

    @Test
    fun parse_server_state_with_metadata() {
        val json = """
            {
              "type": "server/state",
              "payload": {
                "metadata": {
                  "title": "Test Track",
                  "artist": "Test Artist",
                  "album": "Test Album",
                  "artwork_url": "http://example.com/art.jpg",
                  "progress": { "track_progress": 30000, "track_duration": 240000, "playback_speed": 1000 }
                }
              }
            }
        """.trimIndent()

        val msg = assertIs<ServerState>(parser.parseText(json))
        assertEquals(JsonOptional.Present("Test Track"), msg.metadata?.title)
        assertEquals(JsonOptional.Present("Test Artist"), msg.metadata?.artist)
        assertEquals(30000L, msg.metadata?.progress?.trackProgress)
        assertEquals(240000L, msg.metadata?.progress?.trackDuration)
    }

    @Test
    fun parse_server_state_absent_field_is_Absent() {
        val json = """{"type":"server/state","payload":{"metadata":{"title":"Radio Station"}}}"""
        val msg = assertIs<ServerState>(parser.parseText(json))
        assertEquals(JsonOptional.Present("Radio Station"), msg.metadata?.title)
        assertEquals(JsonOptional.Absent, msg.metadata?.artist)
        assertEquals(JsonOptional.Absent, msg.metadata?.album)
    }

    @Test
    fun parse_server_state_explicit_null_is_Present_null() {
        val json = """{"type":"server/state","payload":{"metadata":{"title":"Radio Station","artist":null,"album":null}}}"""
        val msg = assertIs<ServerState>(parser.parseText(json))
        assertEquals(JsonOptional.Present("Radio Station"), msg.metadata?.title)
        assertEquals(JsonOptional.Present(null), msg.metadata?.artist)
        assertEquals(JsonOptional.Present(null), msg.metadata?.album)
    }

    @Test
    fun parse_stream_start() {
        val json = """
            {"type":"stream/start","payload":{"player":{"codec":"opus","sample_rate":48000,"channels":2,"bit_depth":16}}}
        """.trimIndent()
        val msg = assertIs<StreamStart>(parser.parseText(json))
        val player = assertNotNull(msg.player)
        assertEquals("opus", player.codec)
        assertEquals(48000, player.sampleRate)
        assertEquals(2, player.channels)
    }

    @Test
    fun parse_stream_clear() {
        assertIs<StreamClear>(parser.parseText("""{"type":"stream/clear"}"""))
    }

    @Test
    fun parse_stream_end_no_roles() {
        val msg = assertIs<StreamEnd>(parser.parseText("""{"type":"stream/end"}"""))
        assertNull(msg.roles)
    }

    @Test
    fun parse_stream_end_with_roles() {
        val msg = assertIs<StreamEnd>(parser.parseText("""{"type":"stream/end","payload":{"roles":["player"]}}"""))
        assertEquals(listOf("player"), msg.roles)
    }

    @Test
    fun parse_server_time() {
        val json = """{"type":"server/time","payload":{"client_transmitted":1000000,"server_received":1010000,"server_transmitted":1010100}}"""
        val msg = assertIs<ServerTime>(parser.parseText(json))
        assertEquals(1_000_000L, msg.clientTime)
        assertEquals(1_010_000L, msg.serverReceive)
        assertEquals(1_010_100L, msg.serverSend)
    }

    @Test
    fun parse_group_update_playing() {
        val msg = assertIs<GroupUpdate>(parser.parseText("""{"type":"group/update","payload":{"playback_state":"playing"}}"""))
        assertEquals("playing", msg.playbackState)
        assertEquals(GroupPlaybackState.PLAYING, msg.typedPlaybackState)
    }

    @Test
    fun parse_group_update_paused() {
        val msg = assertIs<GroupUpdate>(parser.parseText("""{"type":"group/update","payload":{"playback_state":"paused"}}"""))
        assertEquals(GroupPlaybackState.PAUSED, msg.typedPlaybackState)
    }

    @Test
    fun parse_group_update_stopped() {
        val msg = assertIs<GroupUpdate>(parser.parseText("""{"type":"group/update","payload":{"playback_state":"stopped"}}"""))
        assertEquals(GroupPlaybackState.STOPPED, msg.typedPlaybackState)
    }

    @Test
    fun parse_group_update_unknown_playback_state_returns_null_typed() {
        val msg = assertIs<GroupUpdate>(parser.parseText("""{"type":"group/update","payload":{"playback_state":"rewinding"}}"""))
        assertEquals("rewinding", msg.playbackState)
        assertNull(msg.typedPlaybackState)
    }

    @Test
    fun parse_group_update_with_no_playback_state() {
        val msg = assertIs<GroupUpdate>(parser.parseText("""{"type":"group/update","payload":{"volume":80}}"""))
        assertNull(msg.playbackState)
        assertNull(msg.typedPlaybackState)
    }

    @Test
    fun unknown_message_type_returns_UnknownMessage() {
        val msg = assertIs<UnknownMessage>(parser.parseText("""{"type":"future/feature","data":"something"}"""))
        assertEquals("future/feature", msg.type)
    }

    @Test
    fun invalid_JSON_returns_null() {
        assertNull(parser.parseText("not json at all {{{"))
    }

    // ── Outgoing message serialization ───────────────────────────────────────

    @Test
    fun serialize_client_command_play() {
        val json = ProtocolJson.encodeToString(
            ClientCommand.serializer(),
            ClientCommand(payload = ClientCommandPayload(controller = ClientCommandControllerPayload(command = "play"))),
        )
        assertTrue(json.contains(""""type":"client/command""""))
        assertTrue(json.contains(""""command":"play""""))
    }

    @Test
    fun serialize_client_command_mute_uses_muted_field_name() {
        val json = ProtocolJson.encodeToString(
            ClientCommand.serializer(),
            ClientCommand(payload = ClientCommandPayload(controller = ClientCommandControllerPayload(command = "mute", muted = true))),
        )
        assertTrue(json.contains(""""muted":true"""))
    }

    @Test
    fun serialize_client_command_volume_includes_volume_field() {
        val json = ProtocolJson.encodeToString(
            ClientCommand.serializer(),
            ClientCommand(payload = ClientCommandPayload(controller = ClientCommandControllerPayload(command = "volume", volume = 75))),
        )
        assertTrue(json.contains(""""command":"volume""""))
        assertTrue(json.contains(""""volume":75"""))
    }

    @Test
    fun serialize_client_state_includes_static_delay_in_player_object() {
        val json = ProtocolJson.encodeToString(
            ClientStateMsg.serializer(),
            ClientStateMsg(payload = ClientStateMsgPayload(player = PlayerStatePayload(staticDelayMs = 200))),
        )
        assertTrue(json.contains(""""type":"client/state""""))
        assertTrue(json.contains(""""state":"synchronized""""))
        assertTrue(json.contains(""""player":{"""))
        assertTrue(json.contains(""""static_delay_ms":200"""))
    }

    @Test
    fun serialize_client_state_with_zero_delay() {
        val json = ProtocolJson.encodeToString(
            ClientStateMsg.serializer(),
            ClientStateMsg(payload = ClientStateMsgPayload(player = PlayerStatePayload(staticDelayMs = 0))),
        )
        assertTrue(json.contains(""""static_delay_ms":0"""))
    }

    @Test
    fun serialize_client_state_player_includes_volume_and_muted_when_present() {
        val json = ProtocolJson.encodeToString(
            ClientStateMsg.serializer(),
            ClientStateMsg(payload = ClientStateMsgPayload(player = PlayerStatePayload(volume = 75, muted = false, staticDelayMs = 100))),
        )
        assertTrue(json.contains(""""volume":75"""))
        assertTrue(json.contains(""""muted":false"""))
        assertTrue(json.contains(""""static_delay_ms":100"""))
    }

    @Test
    fun serialize_client_state_includes_timing_fields() {
        val json = ProtocolJson.encodeToString(
            ClientStateMsg.serializer(),
            ClientStateMsg(
                payload = ClientStateMsgPayload(
                    player = PlayerStatePayload(staticDelayMs = 100, requiredLeadTimeMs = 250, minBufferMs = 500),
                ),
            ),
        )
        assertTrue(json.contains(""""static_delay_ms":100"""))
        assertTrue(json.contains(""""required_lead_time_ms":250"""))
        assertTrue(json.contains(""""min_buffer_ms":500"""))
    }

    @Test
    fun parse_server_state_with_color() {
        val json = """
            {"type":"server/state","payload":{"color":{
              "timestamp":5000000,"background_dark":[10,20,30],"background_light":[200,210,220],
              "primary":[255,0,0],"accent":[0,255,0],"on_dark":[240,240,240],"on_light":[20,20,20]}}}
        """.trimIndent()
        val msg = assertIs<ServerState>(parser.parseText(json))
        val color = assertNotNull(msg.color)
        assertEquals(5_000_000L, color.timestamp)
        assertEquals(listOf(10, 20, 30), color.backgroundDark)
        assertEquals(listOf(200, 210, 220), color.backgroundLight)
        assertEquals(listOf(255, 0, 0), color.primary)
        assertEquals(listOf(0, 255, 0), color.accent)
        assertEquals(listOf(240, 240, 240), color.onDark)
        assertEquals(listOf(20, 20, 20), color.onLight)
    }

    @Test
    fun parse_server_state_color_with_null_fields() {
        val json = """{"type":"server/state","payload":{"color":{"timestamp":1000,"primary":[128,64,32]}}}"""
        val msg = assertIs<ServerState>(parser.parseText(json))
        val color = assertNotNull(msg.color)
        assertEquals(1000L, color.timestamp)
        assertEquals(listOf(128, 64, 32), color.primary)
        assertNull(color.backgroundDark)
        assertNull(color.backgroundLight)
        assertNull(color.accent)
        assertNull(color.onDark)
        assertNull(color.onLight)
    }

    @Test
    fun parse_server_state_controller_with_repeat_and_shuffle() {
        val json = """{"type":"server/state","payload":{"controller":{"supported_commands":["volume"],"volume":80,"muted":false,"repeat":"all","shuffle":true}}}"""
        val msg = assertIs<ServerState>(parser.parseText(json))
        assertEquals(JsonOptional.Present("all"), msg.controller?.repeat)
        assertEquals(JsonOptional.Present(true), msg.controller?.shuffle)
        assertEquals(80, msg.controller?.volume)
    }

    @Test
    fun parse_server_state_controller_without_repeat_and_shuffle_defaults_to_Absent() {
        val json = """{"type":"server/state","payload":{"controller":{"volume":100,"muted":false}}}"""
        val msg = assertIs<ServerState>(parser.parseText(json))
        assertEquals(JsonOptional.Absent, msg.controller?.repeat)
        assertEquals(JsonOptional.Absent, msg.controller?.shuffle)
    }

    @Suppress("DEPRECATION")
    @Test
    fun parse_server_state_metadata_with_legacy_repeat_and_shuffle() {
        val json = """{"type":"server/state","payload":{"metadata":{"title":"Old Server Track","repeat":"one","shuffle":false}}}"""
        val msg = assertIs<ServerState>(parser.parseText(json))
        assertEquals(JsonOptional.Present("one"), msg.metadata?.repeat)
        assertEquals(JsonOptional.Present(false), msg.metadata?.shuffle)
    }

    @Test
    fun parse_stream_end_with_versioned_role_names() {
        val msg = assertIs<StreamEnd>(
            parser.parseText("""{"type":"stream/end","payload":{"roles":["player@v1","metadata@v1"]}}"""),
        )
        assertEquals(listOf("player@v1", "metadata@v1"), msg.roles)
    }

    // ── Binary messages ───────────────────────────────────────────────────────

    @Test
    fun parse_binary_audio_chunk() {
        val timestamp = 123_456_789_000L
        val audioData = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val result = assertIs<MessageParser.BinaryAudio>(
            parser.parseBinary(buildBinaryFrame(BINARY_TYPE_AUDIO, timestamp, audioData)),
        )
        assertEquals(timestamp, result.chunk.serverTimestampMicros)
        assertTrue(audioData.contentEquals(result.chunk.data))
    }

    @Test
    fun parse_binary_artwork_channel_0() {
        val timestamp = 9_999L
        val imgData = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val result = assertIs<MessageParser.BinaryArtwork>(
            parser.parseBinary(buildBinaryFrame(BINARY_TYPE_ARTWORK_0, timestamp, imgData)),
        )
        assertEquals(0, result.channel)
        assertEquals(timestamp, result.serverTimestampMicros)
        assertTrue(imgData.contentEquals(result.data))
    }

    @Test
    fun binary_frame_too_short_returns_null() {
        assertNull(parser.parseBinary(ByteArray(4) { 0x04 }))
    }

    @Test
    fun binary_frame_empty_payload_is_valid_audio_chunk() {
        val result = assertIs<MessageParser.BinaryAudio>(
            parser.parseBinary(buildBinaryFrame(BINARY_TYPE_AUDIO, 0L, ByteArray(0))),
        )
        assertEquals(0, result.chunk.data.size)
    }

    // ── Visualizer binary messages ────────────────────────────────────────────

    @Test
    fun parse_visualizer_loudness() {
        val ts = 500_000L
        val result = assertIs<MessageParser.BinaryVisualizer>(
            parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_LOUDNESS, ts, byteArrayOf(0x80.toByte(), 0x00))),
        )
        val frame = assertIs<VisualizerFrame.Loudness>(result.frame)
        assertEquals(ts, frame.serverTimestampMicros)
        assertEquals(32768, frame.value)
    }

    @Test
    fun visualizer_loudness_too_short_returns_null() {
        assertNull(parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_LOUDNESS, 0L, byteArrayOf(0x01))))
    }

    @Test
    fun parse_visualizer_beat_without_downbeat() {
        val ts = 1_000_000L
        val result = assertIs<MessageParser.BinaryVisualizer>(
            parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_BEAT, ts, byteArrayOf(0x00))),
        )
        val frame = assertIs<VisualizerFrame.Beat>(result.frame)
        assertEquals(ts, frame.serverTimestampMicros)
        assertEquals(false, frame.isDownbeat)
    }

    @Test
    fun parse_visualizer_beat_with_downbeat() {
        val result = assertIs<MessageParser.BinaryVisualizer>(
            parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_BEAT, 0L, byteArrayOf(0x01))),
        )
        assertTrue(assertIs<VisualizerFrame.Beat>(result.frame).isDownbeat)
    }

    @Test
    fun visualizer_beat_too_short_returns_null() {
        assertNull(parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_BEAT, 0L, byteArrayOf())))
    }

    @Test
    fun parse_visualizer_f_peak() {
        val ts = 2_000_000L
        val payload = byteArrayOf(0x04, 0x40, 0xFF.toByte(), 0xFF.toByte())
        val result = assertIs<MessageParser.BinaryVisualizer>(
            parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_F_PEAK, ts, payload)),
        )
        val frame = assertIs<VisualizerFrame.FPeak>(result.frame)
        assertEquals(ts, frame.serverTimestampMicros)
        assertEquals(0x0440, frame.freqHz)
        assertEquals(65535, frame.amplitude)
    }

    @Test
    fun visualizer_f_peak_too_short_returns_null() {
        assertNull(parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_F_PEAK, 0L, byteArrayOf(0x01, 0x02))))
    }

    @Test
    fun parse_visualizer_spectrum() {
        val ts = 3_000_000L
        val payload = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0xFF.toByte())
        val result = assertIs<MessageParser.BinaryVisualizer>(
            parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_SPECTRUM, ts, payload)),
        )
        val frame = assertIs<VisualizerFrame.Spectrum>(result.frame)
        assertEquals(ts, frame.serverTimestampMicros)
        assertEquals(2, frame.bins.size)
        assertEquals(1.toShort(), frame.bins[0])
        assertEquals((-1).toShort(), frame.bins[1])
    }

    @Test
    fun parse_visualizer_spectrum_with_empty_payload() {
        val result = assertIs<MessageParser.BinaryVisualizer>(
            parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_SPECTRUM, 0L, byteArrayOf())),
        )
        assertEquals(0, assertIs<VisualizerFrame.Spectrum>(result.frame).bins.size)
    }

    @Test
    fun parse_visualizer_peak() {
        val ts = 4_000_000L
        val result = assertIs<MessageParser.BinaryVisualizer>(
            parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_PEAK, ts, byteArrayOf(0xAB.toByte()))),
        )
        val frame = assertIs<VisualizerFrame.Peak>(result.frame)
        assertEquals(ts, frame.serverTimestampMicros)
        assertEquals(0xAB, frame.strength)
    }

    @Test
    fun visualizer_peak_too_short_returns_null() {
        assertNull(parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_PEAK, 0L, byteArrayOf())))
    }

    @Test
    fun parse_visualizer_pitch() {
        val ts = 5_000_000L
        val payload = byteArrayOf(0x45, 0x80.toByte(), 0xC8.toByte())
        val result = assertIs<MessageParser.BinaryVisualizer>(
            parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_PITCH, ts, payload)),
        )
        val frame = assertIs<VisualizerFrame.Pitch>(result.frame)
        assertEquals(ts, frame.serverTimestampMicros)
        assertEquals(0x4580, frame.midiFixed88)
        assertEquals(200, frame.confidence)
    }

    @Test
    fun visualizer_pitch_too_short_returns_null() {
        assertNull(parser.parseBinary(buildBinaryFrame(BINARY_TYPE_VISUALIZER_PITCH, 0L, byteArrayOf(0x45, 0x80.toByte()))))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildBinaryFrame(type: Byte, timestamp: Long, payload: ByteArray): ByteArray {
        val out = ByteArray(1 + 8 + payload.size)
        out[0] = type
        for (i in 0 until 8) {
            out[1 + i] = (timestamp ushr (8 * (7 - i))).toByte()
        }
        payload.copyInto(out, destinationOffset = 9)
        return out
    }
}
