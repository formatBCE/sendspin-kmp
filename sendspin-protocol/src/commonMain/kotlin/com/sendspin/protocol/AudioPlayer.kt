package com.sendspin.protocol

/**
 * Host-implemented audio sink. Constructed via the `audioPlayerFactory` passed to [SendSpinClient],
 * which hands it the [AudioBuffer] (to pull scheduled chunks from) and the [ClockSync] (to convert
 * server timestamps). The implementation owns decoding (chunks are raw encoded bytes) and hardware
 * output; the library owns buffering and scheduling policy.
 */
interface AudioPlayer {
    val isPlaying: Boolean
    val droppedDecodeFrames: Long
    fun configure(format: StreamFormat)
    fun start()
    fun flush()
    fun stop()
    fun transition(format: StreamFormat)
    /** Apply a linear gain in [0.0, 1.0] derived from the perceptual volume curve. */
    fun setVolume(gain: Float)
}
