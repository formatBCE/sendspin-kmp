# sendspin-kmp — divergences from sendspin-jvm

Audience: the **sendspin-jvm** maintainer.

This document catalogs how `sendspin-kmp` (`com.sendspin:sendspin-protocol`, package
`com.sendspin.protocol`) differs from the canonical JVM library it was forked from. It is split into
two parts so you can triage quickly:

- **Part A — Port mechanics.** Structural/platform changes made to run on Kotlin Multiplatform.
  These are *not* behavior changes and are generally **not** things to adopt into the JVM library —
  they exist only because KMP forbids JVM-only APIs. Listed for context and to explain why some
  files moved or changed shape.
- **Part B — Fixes & behavioral divergences.** These *are* candidates to adopt. Two are correctness
  bugs present in sendspin-jvm and should be taken regardless of anything else: **B1** (certain) and
  **B5** (very likely — see its caveat). The rest are opt-in behavioral choices (B2/B3 are a linked
  pair; B4 are additive config knobs).

Line numbers are indicative (they drift); grep the named symbols to locate current sites.

---

## Scope difference (one paragraph)

`sendspin-kmp` is a **client-only** port. It drops the JVM library's server-host and local-discovery
surface entirely: `SendSpinServerHost.kt`, `ClientAdvertiser.kt`, `DiscoveryService.kt`,
`NsdBrowser.kt`, `NsdRegistrar.kt`, and the server-initiated `SendSpinWebSocket` adapters are **not
ported**. Everything below concerns the client path (`SendSpinClient`, `ClockSync`, `AudioBuffer`,
`AudioPlayer`, `MessageParser`, transport).

---

# Part A — Port mechanics (context only; do not adopt)

### A1. Build / platform
| | sendspin-jvm | sendspin-kmp |
|---|---|---|
| Plugin | `kotlin("jvm")` | `kotlinMultiplatform` + `kotlinSerialization` |
| Targets | JVM | `androidTarget`, `jvm()` (tests only), `iosArm64`, `iosSimulatorArm64` |
| JSON | Moshi + KSP codegen (`*JsonAdapter` classes) | `kotlinx.serialization` |
| WebSocket | OkHttp (client) + Java-WebSocket (server) | Ktor `client-websockets` |
| Concurrency | `java`/`kotlin.jvm` `@Volatile`, `synchronized(Any())` | `kotlinx.atomicfu` (`SynchronizedObject`, `synchronized`), `kotlin.concurrent.Volatile` |
| Monotonic time | `System.nanoTime()` | `kotlin.time.TimeSource.Monotonic` |

### A2. Transport abstraction (`SendSpinWebSocket` → `SendSpinTransport`)
The JVM library has a thin `SendSpinWebSocket { send; close }` with OkHttp/Java-WebSocket adapters,
and `SendSpinClient` drives the OkHttp `WebSocketListener` directly. The KMP port introduces a
first-class carrier abstraction:

- **`SendSpinTransport`** — `state: StateFlow<TransportState>`, **`frames: Flow<TransportFrame>`**,
  `connect()/send(text)/send(bytes)/disconnect()/close()`.
- **`TransportFrame`** — a sealed `Text(text)` / `Binary(bytes)`, so control and audio frames travel
  through **one ordered flow** in exact wire order.
- **`KtorWebSocketTransport`** — the Ktor implementation.
- `SendSpinClient` takes a **`transportFactory: () -> SendSpinTransport`** and is fully
  carrier-agnostic (the consuming app also plugs a WebRTC data-channel transport and an
  auth-handshake-decorator transport through the same interface).

This is the largest structural change. It is a KMP/architecture decision, not a bug fix, but it is
the reason several client-side seams below look different from the JVM code.

**A cautionary note on the single `frames` flow — this is us fixing our own mistake, not yours.**
The port originally exposed `textFrames: Flow<String>` and `binaryFrames: Flow<ByteArray>` as two
separate flows, collected by two coroutines in `SendSpinClient.doConnect`. That silently destroyed
wire ordering: a straggler audio chunk sitting in one flow's buffer could be handled *after* a
`stream/start` that arrived behind it on the wire, so the discontinuity marker no longer sat between
the last old-stream chunk and the first new-stream chunk. On a skip, the two streams interleaved by
server timestamp and played as **two tracks mixed together** (rarely, and worse on cellular, where
transport backlogs are deeper).

**sendspin-jvm cannot hit this and needs no change here.** OkHttp's `WebSocketListener` delivers
`onMessage(text)` and `onMessage(bytes)` on a single reader thread, straight into
`handleTextMessage`/`handleBinaryMessage` (`SendSpinClient.kt:303-311`, `:585`) — ordering is
preserved by construction, with no intermediate flow or channel to reorder them. The dual-flow demux
was an artifact of adapting Ktor's `incoming` channel, and collapsing it back to one ordered flow
merely restores the guarantee you already had.

What *does* carry over is the second half of the same fix — making the discontinuity buffer clear
synchronous with the frame that triggers it. See **B5**.

### A3. Time base (`ClockSync.localMicros`)
```kotlin
// jvm
fun localMicros(): Long = System.nanoTime() / 1_000L
// kmp
private val monotonicBase = TimeSource.Monotonic.markNow()   // captured once, process-wide
fun localMicros(): Long = monotonicBase.elapsedNow().inWholeMicroseconds
```
Both are arbitrary-origin monotonic clocks in µs; the elapsed-since-mark form is just the
multiplatform equivalent. **No behavioral difference** — the NTP math only ever uses *differences*
of local timestamps. (Note this for B1: the seeding bug is independent of which clock you use.)

### A4. `AudioBuffer`
Logic is **identical** to the JVM version (same `DROP_THRESHOLD_MICROS = 1s`,
`MAX_FUTURE_MICROS = 30s`, `staticDelayMicros`, binary-search insertion, capacity eviction). The
only change is the lock primitive: `Any()` + `synchronized` → atomicfu `SynchronizedObject`. Nothing
to adopt.

---

# Part B — Fixes & behavioral divergences (adopt these)

## B1. `ClockSync` first-measurement seeding — **CRITICAL BUG, present in sendspin-jvm too**

**This is the one thing every sendspin-jvm user should take.** It was found on-device in the KMP
client but the defect is byte-for-byte present in `sendspin-jvm/ClockSync.kt` (identical
`processMeasurement`/`predict`/`update`).

### The bug
`ClockSync` starts `xOffset = 0.0` and feeds the **first** measurement through the normal Kalman
`update()`. With a real server whose clock is a monotonic ~10¹² µs ahead of the client, the first
innovation is the *entire* offset (~3.45×10¹² µs). Even the microscopic drift Kalman gain
`k1 = p10/s ≈ 1.6e-12` then injects that enormous innovation into the **drift** state:

```
xDrift += k1 × innovation ≈ 1.6e-12 × 3.45e12 ≈ 5.5 µs/µs
```

A drift of 5.5 µs per µs is physically impossible. From then on every `predict()` (≈10 s between
probe bursts) throws `xOffset` off by `5.5 × 10s = 55 s`, and `toLocalMicros()`'s drift
extrapolation compounds it. The offset estimate swings **±tens of seconds** around the true value.

### Observed symptom
Audio chunks are scheduled at `toLocalMicros(serverTs) − staticDelay`. With the offset swinging:
- offset too low → chunks scheduled far in the future → silence until the user seeks;
- offset too high → chunks scheduled in the past → the buffer dumps them all at once → fast/garbled
  "vibrating" playback.

On-device, `ClockSync.offsetMicros` was measured swinging from −11 s to +25 s against a true offset
that was stable to ~1 ms. **Everything downstream (scheduling, decode, sink) was correct — it was
pure garbage-in from the clock.**

### Why the tests didn't catch it
Every existing `ClockSyncTest` uses tiny offsets (20–50 ms), where the first innovation is never
10¹², so the drift never blows up. It only manifests against a real server-magnitude clock.

### The fix — seed the state from the first measurement
Standard remedy for a Kalman filter with a large initial state: don't let the filter *absorb* a
10¹² innovation; **initialize** from it.

```kotlin
// field
private var seeded = false

// inside processMeasurement(t1, t2, t3, t4), replacing `predict(t4); update(...)`:
synchronized(lock) {
    lastRttMicros = rtt
    if (!seeded) {
        // Seed the filter directly from the first measurement. Starting xOffset at 0 would make the
        // first innovation the entire offset (~10^12 µs for a server monotonic clock). Even a
        // microscopic drift gain k1 then leaks that huge innovation into the drift state
        // (xDrift += k1 × ~10^12 ≈ several µs/µs), which every later predict() extrapolates into
        // tens of seconds of offset error. Seeding keeps innovations small.
        seeded = true
        xOffset = offsetEstimate
        xDrift = 0.0
        lastPredictTimeMicros = t4
        p00 = measurementVariance.coerceAtLeast(1.0)
        lastOffsetMicros = xOffset
        lastDriftPpm = 0.0
    } else {
        predict(t4)
        update(offsetEstimate, measurementVariance)
        lastOffsetMicros = xOffset
        lastDriftPpm = xDrift * 1_000_000.0
    }
}
```
If your `ClockSync` has a `reset()` (the KMP one adds it for doze-wake — see B4), also set
`seeded = false` there.

### Regression test (fails before, passes after)
```kotlin
@Test
fun `offset stays accurate and drift stays sane with realistic server clock offset`() {
    val trueOffset = 3_452_369_038_000L // ~3.45e12 µs, a real server monotonic clock
    val rtt = 4_000L
    val t1Start = 1_000_000_000L        // > any realistic test-process uptime, so first dt > 0
    repeat(20) {
        val t1 = t1Start + it * 10_000_000L // 10 s apart — sparse, like real bursts
        val t2 = t1 + trueOffset + rtt / 2
        val t3 = t2 + 50L
        val t4 = t1 + rtt + 50L
        clockSync.processMeasurement(t1, t2, t3, t4)
    }
    assertTrue(kotlin.math.abs(clockSync.lastOffsetMicros - trueOffset) < 10_000.0)
    assertTrue(kotlin.math.abs(clockSync.lastDriftPpm) < 1_000.0) // must stay physically plausible
}
```

### Test that becomes obsolete
`low RTT sample converges faster than high RTT sample` relied on the pre-seed transient residual
(it fed *noiseless* probes where `offsetEstimate == trueOffset` exactly, and only "passed" because
starting from zero left an RTT-dependent residual). With correct seeding both filters converge
exactly on noiseless input, so the strict `<` can no longer hold. Replace it with a test that
exercises the real weighting property — a low-RTT outlier perturbs the estimate more than an
equally-offset high-RTT one:
```kotlin
@Test
fun `low RTT outlier perturbs estimate more than high RTT outlier`() {
    val trueOffset = 40_000L
    fun seed(c: ClockSync) { val t1 = 1_000_000L; val rtt = 1_000L
        c.processMeasurement(t1, t1+trueOffset+rtt/2, t1+trueOffset+rtt/2+50L, t1+rtt+50L) }
    fun outlier(c: ClockSync, rtt: Long) { val t1 = 1_200_000L; val noise = 10_000L
        c.processMeasurement(t1, t1+trueOffset+noise+rtt/2, t1+trueOffset+noise+rtt/2+50L, t1+rtt+50L) }
    val low  = ClockSync().also { seed(it); outlier(it, 1_000L) }
    val high = ClockSync().also { seed(it); outlier(it, 20_000L) }
    assertTrue(kotlin.math.abs(low.lastOffsetMicros - trueOffset) >
               kotlin.math.abs(high.lastOffsetMicros - trueOffset))
}
```

> Status: this fix has already been mirrored into the local `sendspin-jvm` working copy used
> alongside the KMP port, but it has **not** been pushed to your upstream — please verify and adopt.

---

## B2. Audio / network decoupling — behavioral divergence (opt-in)

**sendspin-jvm stops audio on every transport drop.** The OkHttp `WebSocketListener.onFailure` and
`onClosed` both call `cleanupJobs()`, which calls `audioPlayer.stop()` — so any blip (even a clean
close before an immediate reconnect) tears playback down. (Do not confuse this with the
`stream/end` → `SEEK_HANDOFF_MS` → `audioPlayer.stop()` path, which is the track-handoff stop and is
identical in both libraries.)

**sendspin-kmp keeps the audio player draining its buffer across a transport reconnect.** See
`onTransportClosed()`:
```kotlin
private fun onTransportClosed(clean: Boolean) {
    _state.value = ClientState.DISCONNECTED
    // Keep the audio player draining its buffer across the reconnect — the buffer is decoupled from
    // transport churn, so a transient drop must not cut playback. (Diverges from upstream
    // sendspin-jvm, which stops audio on every drop.) A genuine outage drains to starvation and the
    // host tears playback down; a recovery resumes via the next stream/start.
    cancelPeriodicJobs()
    maybeReconnect(immediate = clean)
}
```
Audio is only torn down on an **explicit** disconnect (`disconnect(stopAudio = true)`) or a genuine
reset. The rationale is seamless playback across Wi‑Fi↔cellular handoffs and brief drops.

**Trade-off:** with the buffer surviving a drop, a recovery `stream/start` returns *while audio is
still playing*, which requires B3 to avoid mixing. If you don't want the seamless-reconnect
behavior, keep JVM's stop-on-drop and you don't need B3 either.

---

## B3. Seek/skip buffer flush — **required only if you adopt B2**

Both libraries delegate buffer flushing to the `AudioPlayer` SPI: the `stream/end` handler carries
the comment *"Don't flush here — transition() and configure() flush when the next stream/start
arrives."* That contract is correct **as long as audio is stopped on every drop** (JVM's B2), because
then a reconnect always comes back through a fresh `configure()`.

Once you keep the buffer alive across drops (B2), that assumption breaks. A `stream/start` while
still playing can be either (a) a **reconnect-recovery** (keep the surviving buffer) or (b) a
**seek/skip** (must drop the old position's buffered chunks). If you don't distinguish them, the old
position's chunks — scheduled for imminent playback — interleave *by server timestamp* with the new
position's chunks through the single sink, and you hear **two tracks mixed together** (plus stale
chunks replaying after stop).

The distinguishing signal is whether a `stream/end` preceded this `stream/start`. A seek/skip always
emits `stream/end` first (so `pendingStopJob` is set, or the delayed stop already fired); a
reconnect-recovery does not. In the `StreamStart` handler (note the flush is **synchronous** on the
frame collector — that part is B5, and is orthogonal to the guard below):
```kotlin
// Reconnect-recovery re-issues stream/start with NO preceding stream/end (pendingStopJob null) and
// playback still live — keep the buffer. Everything else (seek / skip / stop→restart) is a
// discontinuity: drop the old position's chunks or they mix with the new position's.
val reconnectResume = audioPlayer.isPlaying && pendingStopJob == null
pendingStopJob?.cancel(); pendingStopJob = null
if (!reconnectResume) audioBuffer.flush()          // synchronous, in wire order (B5)
audioScope.launch {
    if (!reconnectResume) audioPlayer.flushSink()  // async sink drain only — never touches the buffer
    if (audioPlayer.isPlaying) audioPlayer.transition(player)
    else { audioPlayer.configure(player); audioPlayer.start() }
}
```
The `reconnectResume` guard is KMP-only today precisely because it is coupled to B2: without the
keep-buffer-across-drops divergence, every `stream/start`-while-playing *is* a discontinuity, so a
JVM port of B5 flushes unconditionally and needs no guard.

Because the guard reads `pendingStopJob` on the frame collector while the delayed stop job nulls it
on `audioScope`, the field is `@Volatile` here and `StreamEnd`'s `pendingStopJob` assignment was
moved onto the collector too. Upstream needs neither: it writes and reads that field entirely within
`audioScope`.

---

## B4. Additive client config (opt-in, app-behavior-preserving)

These are new `SendSpinClient`/`ClientPreferences` knobs in the KMP port with no JVM equivalent.
None are bug fixes; adopt only if useful to you.

- **`ClientPreferences.advertiseOptionalRoles: Boolean = true`** — when false, `client/hello`
  advertises **only** `player@v1` (no metadata/artwork/controller/color). Required for servers that
  reject a hello carrying roles they don't expect; the consuming app sets it false.
- **`ClientPreferences.playerBufferCapacity: Int`** and **`playerSupportedCommands: List<String>`** —
  drive the advertised `player@v1_support.buffer_capacity` / `supported_commands`.
- **`maxReconnectAttempts` + `connectionExhausted: StateFlow<Boolean>`** — bounded reconnection; the
  flow flips true once attempts are exhausted, letting the host surface a permanent failure instead
  of retrying forever.
- **`ClockSync.reset()`** — re-seeds offset/drift and inflates covariance so the next probe burst
  reconverges quickly (used when the device wakes from doze and `CLOCK_MONOTONIC`'s relationship to
  the server clock may have shifted). Remember to clear the B1 `seeded` flag here.
- **Injectable `ioContext` / `audioContext`** — the KMP client takes coroutine contexts so the host
  can supply a platform IO dispatcher and a single-threaded confined dispatcher that serializes
  `AudioPlayer` calls. (`reconnectEnabled` and `staticDelay` already exist in both libraries.)

---

## B5. The discontinuity flush must be synchronous with the frame that triggers it — **likely bug in sendspin-jvm, independent of B2/B3**

Both libraries route buffer clearing through the `AudioPlayer` SPI. In sendspin-jvm, two of the three
call sites are **synchronous** on the OkHttp reader thread:

- the first-measurement flush — `SendSpinClient.kt:398`, `audioPlayer.flush()`
- `stream/clear` — `SendSpinClient.kt:432`, `audioPlayer.flush()`

The third — the discontinuity `stream/start`, `SendSpinClient.kt:416` — is **asynchronous**: it
defers into `audioScope.launch { … transition() / configure() … }`, and those SPI calls are where the
flush is contractually expected to happen (your own comment on the `stream/end` path, `:444`: *"Don't
flush here — transition() and configure() flush when the next stream/start arrives."*).

### The race

The reader thread does not wait for that coroutine. It keeps parsing frames and calling
`audioBuffer.offer()` for the **new** stream's chunks (`:594`). When the launched coroutine finally
runs and flushes, it clears whatever is in the buffer by then — which now includes new-stream chunks
that arrived after the `stream/start`. They are discarded along with the stale ones.

The symptom upstream is *not* the mixing we saw in KMP (your ordered ingress means no stale chunk can
arrive after the marker — see A2); it is a **clipped or late start after a seek/skip**, proportional
to how many new chunks land inside the window. The window is the coroutine dispatch latency of
`audioScope` (`Dispatchers.IO.limitedParallelism`) measured against a ~20-100 ms chunk cadence — so
usually zero chunks, but it widens under IO-dispatcher contention, and it is pure chance that decides.

### The fix, as ported

Split the two responsibilities the SPI's `flush()` was conflating:

- the **client** clears its own `audioBuffer` **synchronously**, on the thread that handled the
  `stream/start`, so the clear lands in wire order — strictly after the last old chunk was offered and
  strictly before the first new one is;
- the SPI method narrows to **`AudioPlayer.flushSink()`** — drain the hardware sink so already-queued
  old audio stops — and is explicitly documented to **never touch the library buffer**, so it remains
  safe to run late on the audio thread.

See the snippet in B3. A JVM adoption is the same shape minus the `reconnectResume` guard (flush
unconditionally) and can be done on `SendSpinClient` alone; the only breaking change for hosts is the
`flush()` → `flushSink()` rename plus the "must not clear the buffer" contract.

### Caveat — verify this against your reference `AudioPlayer` before believing it

We could not confirm from the library sources alone that a JVM `AudioPlayer` implementation actually
clears the library's `AudioBuffer` inside `transition()`/`configure()`; the comments at `:398`
(*"Delegated through audioPlayer so implementations that don't need timing-based flushing … can
override flush() as a no-op"*) and `:444` assert it as the contract, but the implementations live
outside this repo. If your player instead clears the buffer synchronously before dispatching to its
audio thread, the window closes and B5 is a latent design smell rather than a live bug — the SPI
contract still leaves each host free to reintroduce it. Either way, moving the buffer clear into the
client (where the ordering guarantee actually exists) is the change we would argue for.

---

## Adoption summary

| Item | Kind | Recommended for sendspin-jvm |
|---|---|---|
| **B1** ClockSync seeding | Correctness bug (present in jvm) | **Yes — adopt unconditionally** |
| B2 Audio/network decoupling | Behavioral | Optional (seamless reconnect) |
| B3 Seek/skip flush guard | Correctness, coupled to B2 | **Only if you adopt B2** |
| B4 Config knobs | Additive API | Optional |
| **B5** Synchronous discontinuity flush | Likely correctness bug (present in jvm) | **Yes — independent of B2/B3** (confirm against your `AudioPlayer` first) |
| Part A (port mechanics) | Platform | No |
| Part A2 single `frames` flow | Port artifact — fixes a bug we introduced | No — your OkHttp listener is already ordered |
