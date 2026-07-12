package com.sendspin.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Outgoing messages (client → server) ──────────────────────────────────────

@Serializable
data class DeviceInfo(
    @SerialName("manufacturer") val manufacturer: String? = null,
    @SerialName("product_name") val productName: String? = null,
    @SerialName("software_version") val softwareVersion: String? = null,
)

@Serializable
data class ClientHelloPayload(
    @SerialName("client_id") val clientId: String,
    @SerialName("name") val name: String,
    @SerialName("device_info") val deviceInfo: DeviceInfo? = null,
    @SerialName("mac_address") val macAddress: String? = null,
    @SerialName("version") val version: Int = 1,
    @SerialName("supported_roles") val supportedRoles: List<String>,
    @SerialName("player@v1_support") val playerSupport: PlayerSupport? = null,
    @SerialName("metadata@v1_support") val metadataSupport: MetadataSupport? = null,
    @SerialName("artwork@v1_support") val artworkSupport: ArtworkSupport? = null,
    @SerialName("controller@v1_support") val controllerSupport: ControllerSupport? = null,
    @SerialName("color@v1_support") val colorSupport: ColorSupport? = null,
    @SerialName("visualizer@v1_support") val visualizerSupport: VisualizerSupport? = null,
)

@Serializable
data class ClientHello(
    @SerialName("type") val type: String = "client/hello",
    @SerialName("payload") val payload: ClientHelloPayload,
)

@Serializable
data class PlayerSupport(
    @SerialName("supported_formats") val supportedFormats: List<AudioFormat>,
    @SerialName("buffer_capacity") val bufferCapacity: Int = 262144,
    @SerialName("supported_commands") val supportedCommands: List<String> = listOf("volume", "mute"),
)

@Serializable
data class AudioFormat(
    @SerialName("codec") val codec: String,
    @SerialName("channels") val channels: Int,
    @SerialName("sample_rate") val sampleRate: Int,
    @SerialName("bit_depth") val bitDepth: Int,
)

/** Empty capabilities object — metadata@v1 requires no additional fields. Serialises to {}. */
@Serializable
class MetadataSupport

/** Empty capabilities object — controller@v1 requires no additional fields. Serialises to {}. */
@Serializable
class ControllerSupport

/** Empty capabilities object — color@v1 requires no additional fields. Serialises to {}. */
@Serializable
class ColorSupport

@Serializable
data class VisualizerSupport(
    @SerialName("types") val types: List<String>,
    @SerialName("buffer_capacity") val bufferCapacity: Int,
    @SerialName("rate_max") val rateMax: Int,
    @SerialName("spectrum") val spectrum: VisualizerSpectrumConfig? = null,
)

@Serializable
data class VisualizerSpectrumConfig(
    @SerialName("n_disp_bins") val nDispBins: Int,
    @SerialName("scale") val scale: String,
    @SerialName("f_min") val fMin: Int,
    @SerialName("f_max") val fMax: Int,
)

@Serializable
data class ArtworkSupport(
    @SerialName("channels") val channels: List<ArtworkChannel>,
)

@Serializable
data class ArtworkChannel(
    @SerialName("source") val source: String,
    @SerialName("format") val format: String = "jpeg",
    @SerialName("media_width") val mediaWidth: Int = 800,
    @SerialName("media_height") val mediaHeight: Int = 800,
)

@Serializable
data class PlayerStatePayload(
    @SerialName("volume") val volume: Int? = null,
    @SerialName("muted") val muted: Boolean? = null,
    @SerialName("static_delay_ms") val staticDelayMs: Int = 0,
    @SerialName("required_lead_time_ms") val requiredLeadTimeMs: Int = 0,
    @SerialName("min_buffer_ms") val minBufferMs: Int = 0,
)

@Serializable
data class ClientStateMsgPayload(
    @SerialName("state") val state: String = "synchronized",
    @SerialName("player") val player: PlayerStatePayload? = null,
)

@Serializable
data class ClientStateMsg(
    @SerialName("type") val type: String = "client/state",
    @SerialName("payload") val payload: ClientStateMsgPayload,
)

@Serializable
data class ClientTimePayload(
    @SerialName("client_transmitted") val clientTime: Long,
)

@Serializable
data class ClientTime(
    @SerialName("type") val type: String = "client/time",
    @SerialName("payload") val payload: ClientTimePayload,
)

@Serializable
data class ClientGoodbyePayload(
    @SerialName("reason") val reason: String = "user_request",
)

@Serializable
data class ClientGoodbye(
    @SerialName("type") val type: String = "client/goodbye",
    @SerialName("payload") val payload: ClientGoodbyePayload,
)

@Serializable
data class ClientCommandControllerPayload(
    @SerialName("command") val command: String,
    @SerialName("volume") val volume: Int? = null,
    @SerialName("muted") val muted: Boolean? = null,
)

@Serializable
data class ClientCommandPayload(
    @SerialName("controller") val controller: ClientCommandControllerPayload? = null,
)

@Serializable
data class ClientCommand(
    @SerialName("type") val type: String = "client/command",
    @SerialName("payload") val payload: ClientCommandPayload,
)

// ── Incoming messages (server → client) ──────────────────────────────────────

/** Discriminated union for all server → client JSON messages. */
sealed interface IncomingMessage

@Serializable
data class ServerHello(
    @SerialName("server_id") val serverId: String,
    @SerialName("name") val name: String,
    @SerialName("version") val version: Int,
    @SerialName("active_roles") val activeRoles: List<String>,
    @SerialName("connection_reason") val connectionReason: String? = null,
) : IncomingMessage

@Serializable
data class ServerState(
    @SerialName("metadata") val metadata: TrackMetadataMsg? = null,
    @SerialName("controller") val controller: ControllerState? = null,
    @SerialName("color") val color: ColorState? = null,
) : IncomingMessage

@Serializable
data class TrackMetadataMsg(
    @SerialName("timestamp") val timestamp: Long = 0,
    @SerialName("title") val title: JsonOptional<String> = JsonOptional.Absent,
    @SerialName("artist") val artist: JsonOptional<String> = JsonOptional.Absent,
    @SerialName("album_artist") val albumArtist: JsonOptional<String> = JsonOptional.Absent,
    @SerialName("album") val album: JsonOptional<String> = JsonOptional.Absent,
    @SerialName("artwork_url") val artworkUrl: JsonOptional<String> = JsonOptional.Absent,
    @SerialName("year") val year: JsonOptional<Int> = JsonOptional.Absent,
    @SerialName("track") val track: JsonOptional<Int> = JsonOptional.Absent,
    @SerialName("progress") val progress: ProgressInfo? = null,
    @Deprecated("Sent by old servers only; use ControllerState.repeat instead")
    @SerialName("repeat") val repeat: JsonOptional<String> = JsonOptional.Absent,
    @Deprecated("Sent by old servers only; use ControllerState.shuffle instead")
    @SerialName("shuffle") val shuffle: JsonOptional<Boolean> = JsonOptional.Absent,
)

@Serializable
data class ProgressInfo(
    @SerialName("track_progress") val trackProgress: Long = 0,   // ms
    @SerialName("track_duration") val trackDuration: Long = 0,   // ms, 0 = unknown
    @SerialName("playback_speed") val playbackSpeed: Int = 1000, // 1000 = 1×
)

@Serializable
data class ControllerState(
    @SerialName("supported_commands") val supportedCommands: List<String> = emptyList(),
    @SerialName("volume") val volume: Int? = null,
    @SerialName("muted") val muted: Boolean? = null,
    @SerialName("repeat") val repeat: JsonOptional<String> = JsonOptional.Absent,
    @SerialName("shuffle") val shuffle: JsonOptional<Boolean> = JsonOptional.Absent,
)

@Serializable
data class ColorState(
    @SerialName("timestamp") val timestamp: Long = 0,
    @SerialName("background_dark") val backgroundDark: List<Int>? = null,
    @SerialName("background_light") val backgroundLight: List<Int>? = null,
    @SerialName("primary") val primary: List<Int>? = null,
    @SerialName("accent") val accent: List<Int>? = null,
    @SerialName("on_dark") val onDark: List<Int>? = null,
    @SerialName("on_light") val onLight: List<Int>? = null,
)

@Serializable
data class ServerTime(
    @SerialName("client_transmitted") val clientTime: Long,  // t1: client send time
    @SerialName("server_received") val serverReceive: Long,  // t2
    @SerialName("server_transmitted") val serverSend: Long,  // t3
    // t4 is captured locally when the response arrives — not sent by server
) : IncomingMessage

@Serializable
data class StreamVisualizerConfig(
    @SerialName("types") val types: List<String>,
    @SerialName("rate_max") val rateMax: Int,
    @SerialName("tracks_downbeats") val tracksDownbeats: Boolean = false,
    @SerialName("spectrum") val spectrum: VisualizerSpectrumConfig? = null,
)

@Serializable
data class StreamStart(
    /** Present when an audio stream is active; null when only artwork is being configured. */
    @SerialName("player") val player: StreamFormat? = null,
    @SerialName("artwork") val artwork: StreamArtworkConfig? = null,
    @SerialName("visualizer") val visualizer: StreamVisualizerConfig? = null,
) : IncomingMessage

@Serializable
data class StreamArtworkConfig(
    @SerialName("channels") val channels: List<StreamArtworkChannel>,
)

/** Artwork channel descriptor as sent by the server in stream/start (uses width/height, not media_width/media_height). */
@Serializable
data class StreamArtworkChannel(
    @SerialName("source") val source: String,
    @SerialName("format") val format: String,
    @SerialName("width") val width: Int,
    @SerialName("height") val height: Int,
)

@Serializable
data class StreamFormat(
    @SerialName("codec") val codec: String,
    @SerialName("sample_rate") val sampleRate: Int,
    @SerialName("channels") val channels: Int,
    @SerialName("bit_depth") val bitDepth: Int,
    @SerialName("codec_header") val codecHeader: String? = null,
)

/** Server cleared the buffer (e.g. on seek). */
object StreamClear : IncomingMessage

/** Server stopped one or more role streams. Null [roles] means all active streams. */
@Serializable
data class StreamEnd(
    @SerialName("roles") val roles: List<String>? = null,
) : IncomingMessage

enum class GroupPlaybackState { PLAYING, PAUSED, STOPPED }

@Serializable
data class GroupUpdate(
    @SerialName("playback_state") val playbackState: String? = null,
    @SerialName("volume") val volume: Int? = null,
    @SerialName("muted") val muted: Boolean? = null,
) : IncomingMessage {
    val typedPlaybackState: GroupPlaybackState? get() = when (playbackState) {
        "playing" -> GroupPlaybackState.PLAYING
        "paused" -> GroupPlaybackState.PAUSED
        "stopped" -> GroupPlaybackState.STOPPED
        else -> null
    }
}

/** Player-targeted command within a `server/command` message. */
@Serializable
data class ServerCommandPlayerPayload(
    @SerialName("command") val command: String,
    @SerialName("volume") val volume: Int? = null,
    @SerialName("mute") val mute: Boolean? = null,
    @SerialName("static_delay_ms") val staticDelayMs: Int? = null,
)

/** Server-initiated command, e.g. a per-player volume/mute/delay change. */
@Serializable
data class ServerCommand(
    @SerialName("player") val player: ServerCommandPlayerPayload? = null,
) : IncomingMessage

/** Unrecognised / unhandled message type — ignored gracefully. */
data class UnknownMessage(val type: String) : IncomingMessage

// ── Binary message types ──────────────────────────────────────────────────────

const val BINARY_TYPE_AUDIO: Byte = 0x04
const val BINARY_TYPE_ARTWORK_0: Byte = 0x08
const val BINARY_TYPE_ARTWORK_1: Byte = 0x09
const val BINARY_TYPE_ARTWORK_2: Byte = 0x0A
const val BINARY_TYPE_ARTWORK_3: Byte = 0x0B
const val BINARY_TYPE_VISUALIZER_LOUDNESS: Byte = 0x10
const val BINARY_TYPE_VISUALIZER_BEAT: Byte = 0x11
const val BINARY_TYPE_VISUALIZER_F_PEAK: Byte = 0x12
const val BINARY_TYPE_VISUALIZER_SPECTRUM: Byte = 0x13
const val BINARY_TYPE_VISUALIZER_PEAK: Byte = 0x14
const val BINARY_TYPE_VISUALIZER_PITCH: Byte = 0x15

/** A single visualizer analysis frame from the server, carrying a server-clock timestamp. */
sealed interface VisualizerFrame {
    val serverTimestampMicros: Long

    /** Overall A-weighted loudness. [value] is 0–65535 (−60 dB → 0, 0 dB → 65535). */
    data class Loudness(override val serverTimestampMicros: Long, val value: Int) : VisualizerFrame
    /** Musical beat event. [isDownbeat] is true on bar starts when the server tracks downbeats. */
    data class Beat(override val serverTimestampMicros: Long, val isDownbeat: Boolean) : VisualizerFrame
    /** Dominant FFT bin. [freqHz] 0 = no peak; [amplitude] 0–65535. */
    data class FPeak(override val serverTimestampMicros: Long, val freqHz: Int, val amplitude: Int) : VisualizerFrame
    /** Per-display-bin magnitudes, 0–65535 each, low to high frequency. */
    data class Spectrum(override val serverTimestampMicros: Long, val bins: ShortArray) : VisualizerFrame {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Spectrum) return false
            return serverTimestampMicros == other.serverTimestampMicros && bins.contentEquals(other.bins)
        }
        override fun hashCode(): Int = 31 * serverTimestampMicros.hashCode() + bins.contentHashCode()
    }
    /** Energy onset event (any transient). [strength] 0–255. */
    data class Peak(override val serverTimestampMicros: Long, val strength: Int) : VisualizerFrame
    /** Perceived pitch. [midiFixed88] is an 8.8 fixed-point MIDI note; [confidence] 0–255. */
    data class Pitch(override val serverTimestampMicros: Long, val midiFixed88: Int, val confidence: Int) : VisualizerFrame
}

/** A decoded audio chunk ready for scheduling. */
data class AudioChunk(
    /** Server-clock timestamp in microseconds at which this chunk should begin playing. */
    val serverTimestampMicros: Long,
    /** Raw encoded audio bytes (codec determined by the most recent [StreamStart]). */
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioChunk) return false
        return serverTimestampMicros == other.serverTimestampMicros
    }
    override fun hashCode(): Int = serverTimestampMicros.hashCode()
}
