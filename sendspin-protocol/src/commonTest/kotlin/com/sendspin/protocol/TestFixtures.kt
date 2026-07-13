package com.sendspin.protocol

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** No-op transport for tests that exercise message handling without a real connection.
 *  [stateFlow] and [framesFlow] are public so tests can drive the connection lifecycle and feed
 *  ordered incoming frames. */
class NoopTransport : SendSpinTransport {
    val stateFlow = MutableStateFlow<TransportState>(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = stateFlow
    val framesFlow = MutableSharedFlow<TransportFrame>(extraBufferCapacity = 256)
    override val frames: SharedFlow<TransportFrame> = framesFlow
    val sentText = mutableListOf<String>()
    override suspend fun connect() {}
    override fun send(text: String): Boolean { sentText += text; return true }
    override fun send(bytes: ByteArray): Boolean = true
    override fun disconnect(code: Int, reason: String?) {}
    override fun close() {}
}

/** Records the gains passed to [setVolume]; every other member is inert. */
class RecordingAudioPlayer : AudioPlayer {
    val gains = mutableListOf<Float>()
    override val isPlaying = false
    override val droppedDecodeFrames = 0L
    override fun configure(format: StreamFormat) {}
    override fun start() {}
    override fun flushSink() {}
    override fun stop() {}
    override fun transition(format: StreamFormat) {}
    override fun setVolume(gain: Float) { gains += gain }
}

/** In-memory [ClientSettingsStore] for persistence tests. */
class FakeSettingsStore : ClientSettingsStore {
    val ints = mutableMapOf<String, Int>()
    val strings = mutableMapOf<String, String>()
    override fun getInt(key: String, default: Int) = ints[key] ?: default
    override fun putInt(key: String, value: Int) { ints[key] = value }
    override fun getString(key: String, default: String?) = strings[key] ?: default
    override fun putString(key: String, value: String) { strings[key] = value }
}

/** Builds a [SendSpinClient] wired to a [NoopTransport] for offline message-handling tests. */
fun testClient(
    preferences: ClientPreferences = ClientPreferences(
        supportedFormats = listOf(
            AudioFormat("flac", 2, 48000, 16),
            AudioFormat("opus", 2, 48000, 16),
            AudioFormat("pcm", 2, 48000, 16),
        ),
        artworkChannels = listOf(
            ArtworkChannel("album", "jpeg", 800, 800),
            ArtworkChannel("artist", "jpeg", 1920, 1080),
        ),
    ),
    clientId: String = "test-id",
    clientName: String = "Test TV",
    manufacturer: String = "Acme",
    productName: String = "SmartTV-9000",
    softwareVersion: String = "14",
    macAddress: String? = null,
    settingsStore: ClientSettingsStore = NoOpClientSettingsStore,
    audioPlayer: AudioPlayer = RecordingAudioPlayer(),
): SendSpinClient = SendSpinClient(
    transportFactory = { NoopTransport() },
    preferences = preferences,
    clientId = clientId,
    clientName = clientName,
    manufacturer = manufacturer,
    productName = productName,
    softwareVersion = softwareVersion,
    macAddress = macAddress,
    audioPlayerFactory = { _, _ -> audioPlayer },
    settingsStore = settingsStore,
)
