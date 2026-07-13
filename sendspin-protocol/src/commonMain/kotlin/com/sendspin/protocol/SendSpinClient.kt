package com.sendspin.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.pow
import com.sendspin.protocol.ProtocolLog as Timber

enum class ClientState {
    IDLE, CONNECTING, HANDSHAKING, CLOCK_SYNCING, STREAMING, ERROR, DISCONNECTED
}

/**
 * Snapshot of internal diagnostics, emitted via [SendSpinClient.diagnostics].
 */
data class DiagnosticsSnapshot(
    val state: ClientState = ClientState.IDLE,
    val serverName: String = "",
    val clockOffsetMs: Double = 0.0,
    val clockDriftPpm: Double = 0.0,
    val lastRttMicros: Long = 0L,
    val bufferSize: Int = 0,
    val droppedChunks: Long = 0L,
    val lateChunks: Long = 0L,
    val droppedDecodeFrames: Long = 0L,
    val isAudioPlaying: Boolean = false,
)

data class ClientPreferences(
    val supportedFormats: List<AudioFormat>,
    val artworkChannels: List<ArtworkChannel>,
    val visualizerSupport: VisualizerSupport? = null,
    /** Advertised `player@v1_support.buffer_capacity` — how much audio the server may pre-push. */
    val playerBufferCapacity: Int = 262144,
    /** Advertised `player@v1_support.supported_commands`. */
    val playerSupportedCommands: List<String> = listOf("volume", "mute"),
    /**
     * When true (default), advertise the optional metadata/artwork/controller/color roles and their
     * support objects in client/hello. Set false to advertise ONLY player@v1 — required for servers
     * that reject a hello carrying roles they don't expect.
     */
    val advertiseOptionalRoles: Boolean = true,
)

/**
 * Core SendSpin protocol client, carrier-agnostic via [SendSpinTransport].
 *
 * State machine: IDLE → CONNECTING → HANDSHAKING → CLOCK_SYNCING → STREAMING → DISCONNECTED
 *                                                                             ↘ ERROR (on failure)
 *
 * The client owns reconnection: on a transport close/error it re-creates the transport (via
 * [transportFactory]) with exponential backoff, unless [reconnectEnabled] is false (e.g. a WebRTC
 * data channel whose lifecycle the host owns — the host re-creates the whole client instead).
 */
class SendSpinClient(
    /** Produces a fresh transport per (re)connect. For WebSocket this opens a new session; for a
     *  single-use carrier (WebRTC channel) return the same instance and set [reconnectEnabled] false. */
    private val transportFactory: () -> SendSpinTransport,
    private val preferences: ClientPreferences,
    val clientId: String,
    private val clientName: String = "SendSpin TV",
    private val manufacturer: String,
    private val productName: String,
    private val softwareVersion: String,
    private val macAddress: String? = null,
    audioPlayerFactory: (AudioBuffer, ClockSync) -> AudioPlayer,
    /** Set to false to prevent automatic reconnection on disconnect (e.g. host-owned transports). */
    private val reconnectEnabled: Boolean = true,
    /** Give up (and flag [connectionExhausted]) after this many failed reconnect attempts. */
    private val maxReconnectAttempts: Int = Int.MAX_VALUE,
    private val settingsStore: ClientSettingsStore = NoOpClientSettingsStore,
    /** Context for general work (inject a platform IO dispatcher; defaults to Default). */
    ioContext: CoroutineContext = EmptyCoroutineContext,
    /** Single-threaded context that serialises [AudioPlayer] calls (inject a confined dispatcher). */
    audioContext: CoroutineContext = EmptyCoroutineContext,
) {
    val clockSync = ClockSync()
    val audioBuffer = AudioBuffer(clockSync)
    val audioPlayer: AudioPlayer = audioPlayerFactory(audioBuffer, clockSync)

    private val parser = MessageParser()

    // Tracks whether the first clock measurement has been processed. Used to flush the audio
    // buffer once (after the first offset estimate is available), even if server/state already
    // moved us to STREAMING before the first ServerTime arrived. Reset on each new connection.
    private var firstMeasurementCompleted = false

    // Buffers replies within the current burst so the lowest-RTT sample can be selected
    // (burst-then-best, see ClockSyncConfig.PROBES_PER_BURST). Safe because AudioBuffer recomputes
    // chunk schedules live from the current ClockSync estimate instead of baking them in.
    private val burstReplies = mutableListOf<Pair<ServerTime, Long>>()

    private val scope = CoroutineScope(SupervisorJob() + ioContext)
    private val audioScope = CoroutineScope(SupervisorJob() + audioContext)

    @Volatile private var transport: SendSpinTransport? = null
    private var connectionJob: Job? = null
    private var clockJob: Job? = null
    private var stateJob: Job? = null
    private var diagnosticsJob: Job? = null
    // @Volatile: assigned/cancelled on the frame collector (StreamStart/StreamEnd) but self-nulled by
    // the delayed stop job on audioScope — cross-thread visibility for the reconnectResume guard.
    @Volatile private var pendingStopJob: Job? = null

    private val _state = MutableStateFlow(ClientState.IDLE)
    val state: StateFlow<ClientState> = _state

    private val _connectionExhausted = MutableStateFlow(false)
    /** Becomes true once [maxReconnectAttempts] failed reconnects have elapsed with no success. */
    val connectionExhausted: StateFlow<Boolean> = _connectionExhausted

    private val _serverState = MutableSharedFlow<ServerState>(
        replay = 1, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val serverState: SharedFlow<ServerState> = _serverState.asSharedFlow()

    private val _serverName = MutableStateFlow("")
    val serverName: StateFlow<String> = _serverName

    private val _serverId = MutableStateFlow("")
    val serverId: StateFlow<String> = _serverId

    private val _serverHello = MutableStateFlow<ServerHello?>(null)
    /** The most recent [ServerHello] received from the connected server; null before first hello. */
    val serverHello: StateFlow<ServerHello?> = _serverHello

    private val _diagnostics = MutableStateFlow(DiagnosticsSnapshot())
    val diagnostics: StateFlow<DiagnosticsSnapshot> = _diagnostics

    private val _groupPlaybackState = MutableStateFlow<GroupPlaybackState?>(null)
    val groupPlaybackState: StateFlow<GroupPlaybackState?> = _groupPlaybackState

    private val _streamFormat = MutableStateFlow<StreamFormat?>(null)
    val streamFormat: StateFlow<StreamFormat?> = _streamFormat

    private val _streamArtwork = MutableStateFlow<StreamArtworkConfig?>(null)
    /** Artwork channel configuration from the most recent `stream/start`; null until received. */
    val streamArtwork: StateFlow<StreamArtworkConfig?> = _streamArtwork

    private val _controllerState = MutableStateFlow<ControllerState?>(null)
    val controllerState: StateFlow<ControllerState?> = _controllerState

    private val _colorState = MutableStateFlow<ColorState?>(null)
    val colorState: StateFlow<ColorState?> = _colorState

    private val _visualizerStreamConfig = MutableStateFlow<StreamVisualizerConfig?>(null)
    val visualizerStreamConfig: StateFlow<StreamVisualizerConfig?> = _visualizerStreamConfig

    @Volatile private var staticDelayMs: Int = settingsStore.getInt(ClientSettingsKeys.STATIC_DELAY_MS, 0)
        .coerceIn(0, 5000)
    init {
        audioBuffer.staticDelayMicros = staticDelayMs * 1_000L
    }
    @Volatile private var requiredLeadTimeMs: Int = 0
    @Volatile private var minBufferMs: Int = 0

    // Per-player volume/mute, as set via server/command (player.command = "volume" | "mute").
    // Default to full volume / unmuted: the spec requires these fields be present in client/state
    // whenever "volume"/"mute" are in supported_commands, even before the server has commanded.
    @Volatile private var playerVolume: Int = 100
    @Volatile private var playerMuted: Boolean = false

    fun setStaticDelayMs(delayMs: Int) {
        staticDelayMs = delayMs.coerceIn(0, 5000)
        audioBuffer.staticDelayMicros = staticDelayMs * 1_000L
        settingsStore.putInt(ClientSettingsKeys.STATIC_DELAY_MS, staticDelayMs)
        sendClientState()
        // Chunks already in the buffer keep their old scheduled times; the new delay applies only to
        // newly arriving chunks, resolving within one server buffer window (~2 s). A full flush would
        // cause a noticeable gap, so we accept the gradual crossover.
    }

    fun setRequiredLeadTimeMs(ms: Int) {
        requiredLeadTimeMs = ms.coerceAtLeast(0)
        sendClientState()
    }

    fun setMinBufferMs(ms: Int) {
        minBufferMs = ms.coerceAtLeast(0)
        sendClientState()
    }

    private val _albumArtwork = MutableStateFlow<ByteArray?>(null)
    val albumArtwork: StateFlow<ByteArray?> = _albumArtwork

    private val _artistArtwork = MutableStateFlow<ByteArray?>(null)
    val artistArtwork: StateFlow<ByteArray?> = _artistArtwork

    /** The `connection_reason` from the most recent `server/hello`; null until first hello. */
    @Volatile var lastConnectionReason: String? = null
        private set

    fun toLocalMicros(serverMicros: Long): Long = clockSync.toLocalMicros(serverMicros)

    private var serverNameStr = ""
    private var reconnectAttempt = 0

    // ── Public API ────────────────────────────────────────────────────────────

    /** Connect using a fresh transport from [transportFactory]. Idempotent while already connecting. */
    fun connect() {
        if (_state.value == ClientState.CONNECTING) return
        reconnectAttempt = 0
        doConnect()
    }

    fun sendControllerCommand(command: String, volume: Int? = null, muted: Boolean? = null) {
        val t = transport
        if (t == null) {
            Timber.w("SendSpinClient: cannot send client/command controller=%s; disconnected", command)
            return
        }
        val msg = ClientCommand(
            payload = ClientCommandPayload(
                controller = ClientCommandControllerPayload(command = command, volume = volume, muted = muted),
            ),
        )
        val queued = t.send(ProtocolJson.encodeToString(ClientCommand.serializer(), msg))
        if (queued) Timber.d("SendSpinClient: >> client/command controller=%s volume=%s muted=%s", command, volume, muted)
        else Timber.w("SendSpinClient: failed to queue client/command controller=%s", command)
    }

    /**
     * Send goodbye and disconnect the transport, suppressing reconnect.
     *
     * @param stopAudio when false (a warm restart), the audio buffer + player are left alive to keep
     *   draining any buffered audio; the host reconnects later via [connect] to resume. When true,
     *   the player is stopped and the sink released for reuse.
     */
    fun disconnect(reason: String = "user_request", stopAudio: Boolean = true) {
        reconnectAttempt = Int.MAX_VALUE / 2 // suppress reconnect
        val t = transport
        t?.send(ProtocolJson.encodeToString(ClientGoodbye.serializer(), ClientGoodbye(payload = ClientGoodbyePayload(reason = reason))))
        t?.disconnect(1000, reason)
        connectionJob?.cancel(); connectionJob = null
        cancelPeriodicJobs()
        if (stopAudio) stopAudioPlayer()
        clearSessionState()
        _state.value = ClientState.DISCONNECTED
    }

    /** Full teardown — disconnects and releases all scopes and the transport. Unusable afterwards. */
    fun close() {
        disconnect()
        transport?.close(); transport = null
        scope.cancel()
        audioScope.cancel()
    }

    /** Serialised `client/hello`, exposed for tests. */
    internal fun buildClientHelloJson(): String =
        ProtocolJson.encodeToString(ClientHello.serializer(), buildClientHello())

    // ── Connection ────────────────────────────────────────────────────────────

    private fun doConnect() {
        // Tear down any previous connection so its collectors can't double-fire.
        connectionJob?.cancel()
        transport?.close()

        _state.value = ClientState.CONNECTING
        Timber.i("doConnect → CONNECTING, creating transport")
        val t = transportFactory()
        transport = t
        connectionJob = scope.launch {
            launch {
                t.state.collect { st ->
                    when (st) {
                        TransportState.Connecting -> { /* already CONNECTING */ }
                        TransportState.Connected -> onTransportConnected()
                        TransportState.Disconnected -> onTransportClosed(clean = true)
                        is TransportState.Error -> onTransportError(st.cause)
                    }
                }
            }
            // ONE collector over the single ordered frame flow: text and binary are handled in exact
            // wire order on one coroutine (sequential even on a multi-threaded dispatcher), so a
            // stream/start's synchronous buffer clear lands between the right audio chunks.
            launch {
                t.frames.collect { frame ->
                    when (frame) {
                        is TransportFrame.Text -> handleTextMessage(frame.text)
                        is TransportFrame.Binary -> handleBinaryMessage(frame.bytes)
                    }
                }
            }
            t.connect()
        }
    }

    private fun onTransportConnected() {
        Timber.i("transport CONNECTED (state was %s) → HANDSHAKING, sending hello", _state.value.toString())
        if (_state.value != ClientState.CONNECTING) {
            Timber.w("SendSpinClient: transport connected in unexpected state %s", _state.value)
        }
        _state.value = ClientState.HANDSHAKING
        reconnectAttempt = 0
        _connectionExhausted.value = false
        clearSessionState()
        sendHello()
    }

    private fun onTransportClosed(clean: Boolean) {
        Timber.i("transport CLOSED (clean=%s)", clean.toString())
        _state.value = ClientState.DISCONNECTED
        // Keep the audio player draining its buffer across the reconnect — the buffer is decoupled
        // from transport churn, so a transient drop must not cut playback. (Diverges from upstream
        // sendspin-jvm, which stops audio on every drop.) A genuine outage drains to starvation and
        // the host tears playback down; a recovery resumes via the next stream/start.
        cancelPeriodicJobs()
        maybeReconnect(immediate = clean)
    }

    private fun onTransportError(cause: Throwable) {
        Timber.e(cause, "transport ERROR: %s", cause.message ?: cause.toString())
        _state.value = ClientState.ERROR
        cancelPeriodicJobs()
        maybeReconnect(immediate = false)
    }

    private fun maybeReconnect(immediate: Boolean) {
        if (!reconnectEnabled || reconnectAttempt >= Int.MAX_VALUE / 2) return
        if (reconnectAttempt >= maxReconnectAttempts) {
            Timber.w("SendSpinClient: reconnect attempts exhausted (%d) — giving up", maxReconnectAttempts)
            _connectionExhausted.value = true
            return
        }
        scheduleReconnect(immediate)
    }

    // ── Message handling ──────────────────────────────────────────────────────

    internal fun handleTextMessage(json: String) {
        Timber.d("<< %s", json.take(220))
        when (val msg = parser.parseText(json)) {
            is ServerHello -> handleServerHello(msg)
            is ServerState -> {
                val title = (msg.metadata?.title as? JsonOptional.Present)?.value
                if (title != null) Timber.i("SendSpinClient: server/state title='%s'", title)
                val progress = msg.metadata?.progress
                if (progress != null) Timber.d("SendSpinClient: server/state progress=%dms speed=%d",
                    progress.trackProgress, progress.playbackSpeed)
                val effectiveController = mergeControllerWithMetadata(msg)
                if (effectiveController != null) {
                    _controllerState.value = effectiveController
                }
                if (msg.color != null) _colorState.value = msg.color
                _serverState.tryEmit(msg)
                if (_state.value == ClientState.CLOCK_SYNCING || _state.value == ClientState.STREAMING) {
                    _state.value = ClientState.STREAMING
                }
            }
            is ServerTime -> {
                val t4 = ClockSync.localMicros()
                burstReplies.add(msg to t4)
                if (burstReplies.size >= ClockSyncConfig.PROBES_PER_BURST) {
                    val (best, bestT4) = burstReplies.minByOrNull { (m, replyT4) ->
                        ClockSync.rttMicros(m.clientTime, m.serverReceive, m.serverSend, replyT4)
                    }!!
                    burstReplies.clear()
                    clockSync.processMeasurement(best.clientTime, best.serverReceive, best.serverSend, bestT4)
                }
                if (!firstMeasurementCompleted) {
                    firstMeasurementCompleted = true
                    // Flush chunks that arrived with offset=0 before the first estimate was ready.
                    // Buffer clear is synchronous (ordered on this collector); sink drain is async.
                    audioBuffer.flush()
                    audioScope.launch { audioPlayer.flushSink() }
                }
                if (_state.value == ClientState.CLOCK_SYNCING) {
                    _state.value = ClientState.STREAMING
                }
            }
            is StreamStart -> {
                if (msg.player != null) _streamFormat.value = msg.player
                if (msg.artwork != null) _streamArtwork.value = msg.artwork
                if (msg.visualizer != null) _visualizerStreamConfig.value = msg.visualizer
                val artworkChannels = msg.artwork?.channels ?: emptyList()
                Timber.d("SendSpinClient: stream/start artwork channels=%d", artworkChannels.size)
                artworkChannels.forEachIndexed { i, ch ->
                    Timber.d("SendSpinClient: stream/start artwork ch=%d source=%s %dx%d", i, ch.source, ch.width, ch.height)
                }
                val player = msg.player
                if (player != null) {
                    Timber.i("SendSpinClient: stream/start codec=%s", player.codec)
                    sendClientState()
                    // The buffer clear MUST be synchronous here on the frame collector so it lands in
                    // wire order — strictly after the last old-stream chunk (offered earlier on this
                    // same collector) and before the first new-stream chunk (offered later). An async
                    // hop would let a straggler old chunk slip in after the clear and interleave.
                    // Reconnect-recovery re-issues stream/start with NO preceding stream/end
                    // (pendingStopJob null, still playing) → keep the buffer (see onTransportClosed).
                    // Every other stream/start follows a stream/end (seek/skip/stop→restart) and is a
                    // discontinuity whose stale chunks must be dropped, or the two streams mix.
                    val reconnectResume = audioPlayer.isPlaying && pendingStopJob == null
                    pendingStopJob?.cancel()
                    pendingStopJob = null
                    if (!reconnectResume) audioBuffer.flush()
                    audioScope.launch {
                        // Sink drain is async (audio thread) and does NOT clear the buffer, so it
                        // can't wipe new-stream chunks already offered after the synchronous flush.
                        if (!reconnectResume) audioPlayer.flushSink()
                        if (audioPlayer.isPlaying) {
                            audioPlayer.transition(player)
                        } else {
                            audioPlayer.configure(player)
                            audioPlayer.start()
                        }
                    }
                } else {
                    Timber.d("SendSpinClient: stream/start (artwork-only, no audio)")
                }
            }
            is StreamClear -> {
                Timber.d("SendSpinClient: stream/clear")
                // Same split as the discontinuity stream/start: synchronous buffer clear (ordered on
                // this collector), async sink drain.
                audioBuffer.flush()
                audioScope.launch { audioPlayer.flushSink() }
            }
            is StreamEnd -> {
                val roles = msg.roles
                val endPlayer = roles == null || roles.any { it == "player@v1" || it == "player" }
                val endArtwork = roles == null || roles.any { it == "artwork@v1" || it == "artwork" }
                Timber.d("SendSpinClient: stream/end roles=%s", roles)
                if (endPlayer) {
                    _streamFormat.value = null
                    sendClientState()
                    // Manage pendingStopJob synchronously on the frame collector so a following
                    // stream/start (also on this collector) reliably observes it — the reconnectResume
                    // guard reads pendingStopJob and must see this write. Only the delayed stop runs on
                    // audioScope. Don't flush here — the discontinuity buffer clear happens on the next
                    // stream/start; flushing now would cause underruns during gapless track handoffs.
                    pendingStopJob?.cancel()
                    pendingStopJob = audioScope.launch {
                        delay(SEEK_HANDOFF_MS)
                        pendingStopJob = null
                        audioPlayer.stop()
                    }
                }
                if (endArtwork) {
                    _streamArtwork.value = null
                    _albumArtwork.value = null
                    _artistArtwork.value = null
                }
                val endColor = roles == null || roles.any { it == "color@v1" || it == "color" }
                if (endColor) _colorState.value = null
                val endVisualizer = roles == null || roles.any { it == "visualizer@v1" || it == "visualizer" }
                if (endVisualizer) _visualizerStreamConfig.value = null
            }
            is GroupUpdate -> {
                // Group volume/muted are the aggregate across all players in the group — a UI-facing
                // concept, not this player's own gain (which is driven solely by server/command).
                Timber.d("SendSpinClient: group/update state=%s volume=%s muted=%s",
                    msg.typedPlaybackState, msg.volume, msg.muted)
                msg.typedPlaybackState?.let { _groupPlaybackState.value = it }
                if (msg.volume != null || msg.muted != null) {
                    val current = _controllerState.value
                    _controllerState.value = current?.copy(
                        volume = msg.volume ?: current.volume,
                        muted = msg.muted ?: current.muted,
                    ) ?: ControllerState(volume = msg.volume, muted = msg.muted)
                }
            }
            is ServerCommand -> {
                val player = msg.player ?: return
                Timber.d("SendSpinClient: server/command player command=%s volume=%s mute=%s static_delay_ms=%s",
                    player.command, player.volume, player.mute, player.staticDelayMs)
                when (player.command) {
                    "volume" -> {
                        val requested = player.volume
                        if (requested == null) {
                            Timber.w("SendSpinClient: server/command volume missing 'volume' field, ignoring")
                        } else {
                            val clamped = requested.coerceIn(0, 100)
                            if (clamped != requested) {
                                Timber.w("SendSpinClient: server/command volume=%d out of range, clamped to %d", requested, clamped)
                            }
                            playerVolume = clamped
                            applyVolumeToPlayer()
                            sendClientState()
                        }
                    }
                    "mute" -> {
                        val requested = player.mute
                        if (requested == null) {
                            Timber.w("SendSpinClient: server/command mute missing 'mute' field, ignoring")
                        } else {
                            playerMuted = requested
                            applyVolumeToPlayer()
                            sendClientState()
                        }
                    }
                    "set_static_delay" -> {
                        val ms = player.staticDelayMs
                        if (ms == null) {
                            Timber.w("SendSpinClient: server/command set_static_delay missing 'static_delay_ms' field, ignoring")
                        } else {
                            setStaticDelayMs(ms)
                        }
                    }
                    else -> Timber.d("SendSpinClient: unhandled server/command player.command=%s", player.command)
                }
            }
            is UnknownMessage -> Timber.d("SendSpinClient: unknown message type '%s'", msg.type)
            null -> { /* parse error already logged by MessageParser */ }
        }
    }

    /**
     * Applies this player's own volume/mute (set via server/command) to the audio output,
     * converting the perceived-loudness value (0-100) to a linear gain via (vol/100)^1.5.
     */
    private fun applyVolumeToPlayer() {
        val gain = if (playerMuted) 0f else (playerVolume / 100.0).pow(1.5).toFloat()
        audioPlayer.setVolume(gain)
    }

    @Suppress("DEPRECATION")
    private fun mergeControllerWithMetadata(msg: ServerState): ControllerState? {
        val ctrl = msg.controller
        val rawRepeat = msg.metadata?.repeat ?: JsonOptional.Absent
        val rawShuffle = msg.metadata?.shuffle ?: JsonOptional.Absent
        return when {
            ctrl != null -> {
                // Priority: controller (if Present) > metadata (if Present) > existing stored value.
                // Absent from both sources means the server did not touch the field — preserve it.
                val stored = _controllerState.value
                val repeat = when {
                    ctrl.repeat is JsonOptional.Present -> ctrl.repeat
                    rawRepeat is JsonOptional.Present -> rawRepeat
                    else -> stored?.repeat ?: JsonOptional.Absent
                }
                val shuffle = when {
                    ctrl.shuffle is JsonOptional.Present -> ctrl.shuffle
                    rawShuffle is JsonOptional.Present -> rawShuffle
                    else -> stored?.shuffle ?: JsonOptional.Absent
                }
                ctrl.copy(repeat = repeat, shuffle = shuffle)
            }
            rawRepeat is JsonOptional.Present || rawShuffle is JsonOptional.Present -> {
                // No controller object. Old server sends repeat/shuffle only via metadata.
                // Only update an existing controller state to avoid inventing volume/muted defaults.
                val current = _controllerState.value ?: return null
                current.copy(
                    repeat = if (rawRepeat is JsonOptional.Present) rawRepeat else current.repeat,
                    shuffle = if (rawShuffle is JsonOptional.Present) rawShuffle else current.shuffle,
                )
            }
            else -> null
        }
    }

    /**
     * Optional hook called for every received audio chunk before it enters [audioBuffer].
     * Receives all chunks regardless of clock sync state.
     */
    var onAudioChunk: ((AudioChunk) -> Unit)? = null

    /** Optional hook called for every received visualizer frame. */
    var onVisualizerFrame: ((VisualizerFrame) -> Unit)? = null

    private var firstAudioChunkLogged = false

    internal fun handleBinaryMessage(bytes: ByteArray) {
        when (val msg = parser.parseBinary(bytes)) {
            is MessageParser.BinaryAudio -> {
                onAudioChunk?.invoke(msg.chunk)
                if (!firstAudioChunkLogged) {
                    firstAudioChunkLogged = true
                    Timber.i("SendSpinClient: first audio chunk — ts=%d size=%d bufferSize=%d",
                        msg.chunk.serverTimestampMicros, msg.chunk.data.size, audioBuffer.size)
                }
                audioBuffer.offer(msg.chunk)
            }
            is MessageParser.BinaryVisualizer -> {
                onVisualizerFrame?.invoke(msg.frame)
            }
            is MessageParser.BinaryArtwork -> {
                val data = if (msg.data.isEmpty()) null else msg.data
                Timber.d("SendSpinClient: artwork binary ch=%d size=%d -> %s",
                    msg.channel, msg.data.size, if (data == null) "clear" else "set")
                when (preferences.artworkChannels.getOrNull(msg.channel)?.source) {
                    "album" -> _albumArtwork.value = data
                    "artist" -> _artistArtwork.value = data
                    else -> Timber.w("SendSpinClient: artwork binary unknown channel %d", msg.channel)
                }
            }
            null -> {
                Timber.w("SendSpinClient: unparseable binary frame size=%d type=0x%s",
                    bytes.size, if (bytes.isNotEmpty()) bytes[0].toHex2() else "??")
            }
        }
    }

    // ── Hello ─────────────────────────────────────────────────────────────────

    private fun buildClientHello(): ClientHello {
        val optional = preferences.advertiseOptionalRoles
        val roles = buildList {
            add("player@v1")
            if (optional) {
                add("metadata@v1"); add("artwork@v1"); add("controller@v1"); add("color@v1")
            }
            if (preferences.visualizerSupport != null) add("visualizer@v1")
        }
        return ClientHello(
            payload = ClientHelloPayload(
                clientId = clientId,
                name = clientName,
                deviceInfo = DeviceInfo(manufacturer, productName, softwareVersion),
                macAddress = macAddress,
                supportedRoles = roles,
                playerSupport = PlayerSupport(
                    supportedFormats = preferences.supportedFormats,
                    bufferCapacity = preferences.playerBufferCapacity,
                    supportedCommands = preferences.playerSupportedCommands,
                ),
                metadataSupport = if (optional) MetadataSupport() else null,
                artworkSupport = if (optional) ArtworkSupport(channels = preferences.artworkChannels) else null,
                controllerSupport = if (optional) ControllerSupport() else null,
                colorSupport = if (optional) ColorSupport() else null,
                visualizerSupport = preferences.visualizerSupport,
            ),
        )
    }

    private fun sendHello() {
        val json = buildClientHelloJson()
        Timber.d(">> client/hello %s", json.take(400))
        val queued = transport?.send(json)
        Timber.d(">> client/hello queued=%s", queued.toString())
    }

    // ── ServerHello ───────────────────────────────────────────────────────────

    private fun handleServerHello(msg: ServerHello) {
        serverNameStr = msg.name
        _serverName.value = msg.name
        _serverId.value = msg.serverId
        _serverHello.value = msg
        lastConnectionReason = msg.connectionReason
        Timber.i("server/hello from '%s' reason=%s → CLOCK_SYNCING (Ready)", serverNameStr, msg.connectionReason ?: "null")
        _state.value = ClientState.CLOCK_SYNCING
        applyVolumeToPlayer()
        sendClientState()
        startClockSync()
        startPeriodicStateReports()
        startDiagnosticsUpdates()
    }

    // ── Clock sync ────────────────────────────────────────────────────────────

    private fun startClockSync() {
        clockJob?.cancel()
        clockJob = scope.launch {
            while (isActive) {
                sendClockBurst()
                delay(ClockSyncConfig.BURST_INTERVAL_MS)
            }
        }
    }

    private suspend fun sendClockBurst() {
        repeat(ClockSyncConfig.PROBES_PER_BURST) {
            val t1 = ClockSync.localMicros()
            transport?.send(ProtocolJson.encodeToString(ClientTime.serializer(), ClientTime(payload = ClientTimePayload(clientTime = t1))))
            delay(ClockSyncConfig.PROBE_INTERVAL_MS)
        }
    }

    // ── Periodic client/state ─────────────────────────────────────────────────

    private fun sendClientState() {
        val player = PlayerStatePayload(
            volume = playerVolume,
            muted = playerMuted,
            staticDelayMs = staticDelayMs,
            requiredLeadTimeMs = requiredLeadTimeMs,
            minBufferMs = minBufferMs,
        )
        transport?.send(ProtocolJson.encodeToString(ClientStateMsg.serializer(), ClientStateMsg(payload = ClientStateMsgPayload(player = player))))
    }

    private fun startPeriodicStateReports() {
        stateJob?.cancel()
        stateJob = scope.launch {
            while (isActive) {
                delay(5_000L)
                sendClientState()
            }
        }
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    private fun startDiagnosticsUpdates() {
        diagnosticsJob?.cancel()
        diagnosticsJob = scope.launch {
            while (isActive) {
                delay(500L)
                _diagnostics.value = DiagnosticsSnapshot(
                    state = _state.value,
                    serverName = serverNameStr,
                    clockOffsetMs = clockSync.lastOffsetMicros / 1000.0,
                    clockDriftPpm = clockSync.lastDriftPpm,
                    lastRttMicros = clockSync.lastRttMicros,
                    bufferSize = audioBuffer.size,
                    droppedChunks = audioBuffer.droppedChunks,
                    lateChunks = audioBuffer.lateChunks,
                    droppedDecodeFrames = audioPlayer.droppedDecodeFrames,
                    isAudioPlaying = audioPlayer.isPlaying,
                )
            }
        }
    }

    // ── Reconnect ─────────────────────────────────────────────────────────────

    private fun scheduleReconnect(immediate: Boolean) {
        scope.launch {
            if (immediate) {
                Timber.i("SendSpinClient: reconnecting immediately")
                reconnectAttempt = 0
            } else {
                val backoffMs = (RECONNECT_BASE_MS * (1L shl reconnectAttempt.coerceAtMost(6))).coerceAtMost(RECONNECT_MAX_MS)
                Timber.i("SendSpinClient: reconnecting in %d ms (attempt %d)", backoffMs, reconnectAttempt + 1)
                delay(backoffMs)
                reconnectAttempt++
            }
            doConnect()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun clearSessionState() {
        _serverName.value = ""
        _serverId.value = ""
        _serverHello.value = null
        serverNameStr = ""
        lastConnectionReason = null
        _groupPlaybackState.value = null
        _controllerState.value = null
        _colorState.value = null
        _visualizerStreamConfig.value = null
        _streamFormat.value = null
        _streamArtwork.value = null
        _albumArtwork.value = null
        _artistArtwork.value = null
        firstAudioChunkLogged = false
        firstMeasurementCompleted = false
        burstReplies.clear()
    }

    /** Cancel the periodic clock/state/diagnostics loops. Does NOT touch the audio player. */
    private fun cancelPeriodicJobs() {
        clockJob?.cancel(); clockJob = null
        stateJob?.cancel(); stateJob = null
        diagnosticsJob?.cancel(); diagnosticsJob = null
        _groupPlaybackState.value = null
        _controllerState.value = null
        _streamFormat.value = null
    }

    private fun stopAudioPlayer() {
        audioScope.launch {
            pendingStopJob?.cancel()
            pendingStopJob = null
            audioPlayer.stop()
        }
    }

    companion object {
        private const val RECONNECT_BASE_MS = 1_000L
        private const val RECONNECT_MAX_MS = 30_000L
        // Natural track-to-track transitions: aiosendspin sends stream/end then stream/start for the
        // next song. If stream/start arrives within this window, the pending stop is cancelled and
        // transition() handles the handoff gaplessly.
        private const val SEEK_HANDOFF_MS = 500L
    }
}
