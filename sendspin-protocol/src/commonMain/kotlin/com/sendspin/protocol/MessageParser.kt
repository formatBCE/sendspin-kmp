package com.sendspin.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.sendspin.protocol.ProtocolLog as Timber

/**
 * The protocol's canonical JSON codec.
 *
 * - `ignoreUnknownKeys` — servers add fields we don't model (Moshi ignored these by default).
 * - `encodeDefaults` — outgoing messages must emit their `type` discriminator and defaulted
 *   fields (Moshi always wrote defaults). Incoming [JsonOptional] fields are decode-only, so
 *   this has no effect on tri-state handling.
 */
internal val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Parses raw WebSocket frames into typed [IncomingMessage] or [AudioChunk].
 *
 * Text frames carry JSON-encoded control/metadata messages; binary frames carry
 * time-stamped audio or artwork payloads.
 *
 * Binary audio frame layout:
 * ```
 * [0]      1 byte  – message type (0x04 = audio, 0x08-0x0B = artwork channels 0-3)
 * [1..8]   8 bytes – big-endian int64 server timestamp in microseconds
 * [9..]    N bytes – codec-encoded audio data
 * ```
 */
class MessageParser {

    // ── Text (JSON) ───────────────────────────────────────────────────────────

    fun parseText(json: String): IncomingMessage? {
        return try {
            val root = ProtocolJson.parseToJsonElement(json).jsonObject
            val type = root["type"]?.jsonPrimitive?.content ?: ""
            // Protocol wraps message-specific fields in a "payload" object.
            val body: JsonObject = (root["payload"] as? JsonObject) ?: root
            when (type) {
                "server/hello"   -> ProtocolJson.decodeFromJsonElement(ServerHello.serializer(), body)
                "server/state"   -> ProtocolJson.decodeFromJsonElement(ServerState.serializer(), body)
                "server/time"    -> ProtocolJson.decodeFromJsonElement(ServerTime.serializer(), body)
                "stream/start"   -> ProtocolJson.decodeFromJsonElement(StreamStart.serializer(), body)
                "stream/clear"   -> StreamClear
                "stream/end"     -> ProtocolJson.decodeFromJsonElement(StreamEnd.serializer(), body)
                "group/update"   -> ProtocolJson.decodeFromJsonElement(GroupUpdate.serializer(), body)
                "server/command" -> ProtocolJson.decodeFromJsonElement(ServerCommand.serializer(), body)
                else             -> UnknownMessage(type).also {
                    Timber.v("MessageParser: unknown message type '%s'", type)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "MessageParser: failed to parse text frame")
            null
        }
    }

    // ── Binary ────────────────────────────────────────────────────────────────

    sealed interface BinaryMessage
    data class BinaryAudio(val chunk: AudioChunk) : BinaryMessage
    data class BinaryArtwork(val channel: Int, val serverTimestampMicros: Long, val data: ByteArray) : BinaryMessage
    data class BinaryVisualizer(val frame: VisualizerFrame) : BinaryMessage

    fun parseBinary(bytes: ByteArray): BinaryMessage? {
        if (bytes.size < 9) {
            Timber.w("MessageParser: binary frame too short (%d bytes)", bytes.size)
            return null
        }
        val msgType = bytes[0]
        val timestamp = bytes.beLongAt(1)
        val payload = bytes.copyOfRange(9, bytes.size)

        return when (msgType) {
            BINARY_TYPE_AUDIO -> BinaryAudio(AudioChunk(timestamp, payload))
            BINARY_TYPE_ARTWORK_0 -> BinaryArtwork(0, timestamp, payload)
            BINARY_TYPE_ARTWORK_1 -> BinaryArtwork(1, timestamp, payload)
            BINARY_TYPE_ARTWORK_2 -> BinaryArtwork(2, timestamp, payload)
            BINARY_TYPE_ARTWORK_3 -> BinaryArtwork(3, timestamp, payload)
            BINARY_TYPE_VISUALIZER_LOUDNESS -> parseVisualizerLoudness(timestamp, payload)
            BINARY_TYPE_VISUALIZER_BEAT     -> parseVisualizerBeat(timestamp, payload)
            BINARY_TYPE_VISUALIZER_F_PEAK   -> parseVisualizerFPeak(timestamp, payload)
            BINARY_TYPE_VISUALIZER_SPECTRUM -> parseVisualizerSpectrum(timestamp, payload)
            BINARY_TYPE_VISUALIZER_PEAK     -> parseVisualizerPeak(timestamp, payload)
            BINARY_TYPE_VISUALIZER_PITCH    -> parseVisualizerPitch(timestamp, payload)
            else -> {
                Timber.v("MessageParser: unknown binary type 0x%s", msgType.toHex2())
                null
            }
        }
    }

    private fun parseVisualizerLoudness(ts: Long, payload: ByteArray): BinaryMessage? {
        if (payload.size < 2) return malformed("loudness", payload.size, 2)
        val value = payload.beShortAt(0).toInt() and 0xFFFF
        return BinaryVisualizer(VisualizerFrame.Loudness(ts, value))
    }

    private fun parseVisualizerBeat(ts: Long, payload: ByteArray): BinaryMessage? {
        if (payload.isEmpty()) return malformed("beat", 0, 1)
        return BinaryVisualizer(VisualizerFrame.Beat(ts, isDownbeat = (payload[0].toInt() and 0x01) != 0))
    }

    private fun parseVisualizerFPeak(ts: Long, payload: ByteArray): BinaryMessage? {
        if (payload.size < 4) return malformed("f_peak", payload.size, 4)
        val freq = payload.beShortAt(0).toInt() and 0xFFFF
        val amp  = payload.beShortAt(2).toInt() and 0xFFFF
        return BinaryVisualizer(VisualizerFrame.FPeak(ts, freq, amp))
    }

    private fun parseVisualizerSpectrum(ts: Long, payload: ByteArray): BinaryMessage? {
        val n = payload.size / 2
        val bins = ShortArray(n) { payload.beShortAt(it * 2) }
        return BinaryVisualizer(VisualizerFrame.Spectrum(ts, bins))
    }

    private fun parseVisualizerPeak(ts: Long, payload: ByteArray): BinaryMessage? {
        if (payload.isEmpty()) return malformed("peak", 0, 1)
        return BinaryVisualizer(VisualizerFrame.Peak(ts, strength = payload[0].toInt() and 0xFF))
    }

    private fun parseVisualizerPitch(ts: Long, payload: ByteArray): BinaryMessage? {
        if (payload.size < 3) return malformed("pitch", payload.size, 3)
        val midi       = payload.beShortAt(0).toInt() and 0xFFFF
        val confidence = payload[2].toInt() and 0xFF
        return BinaryVisualizer(VisualizerFrame.Pitch(ts, midi, confidence))
    }

    private fun malformed(type: String, actual: Int, required: Int): BinaryMessage? {
        Timber.w("MessageParser: visualizer/%s too short (%d bytes, need %d)", type, actual, required)
        return null
    }
}
