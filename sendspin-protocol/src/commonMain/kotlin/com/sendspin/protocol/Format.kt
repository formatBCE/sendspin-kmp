package com.sendspin.protocol

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Minimal common-Kotlin substitute for `String.format`, covering only the `%s` and `%d`
 * placeholders the protocol logs actually use. Both render the next argument via `toString()`.
 * `%%` is a literal percent. Unmatched placeholders are left verbatim.
 *
 * The few float/hex log sites pre-format their argument with [toFixed] / [toHex2] and pass it
 * through a `%s`, so this parser stays trivial.
 */
internal fun formatLog(message: String, args: Array<out Any?>): String {
    if (args.isEmpty()) return message
    val sb = StringBuilder(message.length + 16)
    var argIndex = 0
    var i = 0
    while (i < message.length) {
        val c = message[i]
        if (c == '%' && i + 1 < message.length) {
            when (message[i + 1]) {
                '%' -> { sb.append('%'); i += 2; continue }
                's', 'd' -> {
                    sb.append(if (argIndex < args.size) args[argIndex++].toString() else "%${message[i + 1]}")
                    i += 2; continue
                }
            }
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

/** Fixed-point decimal string with exactly [digits] fractional places (locale-free, for logs). */
internal fun Double.toFixed(digits: Int): String {
    if (isNaN()) return "NaN"
    if (isInfinite()) return if (this > 0) "Inf" else "-Inf"
    var factor = 1L
    repeat(digits) { factor *= 10 }
    val scaled = (abs(this) * factor).roundToLong()
    val intPart = scaled / factor
    val fracPart = scaled % factor
    val sign = if (this < 0 && (intPart != 0L || fracPart != 0L)) "-" else ""
    if (digits == 0) return "$sign$intPart"
    return "$sign$intPart.${fracPart.toString().padStart(digits, '0')}"
}

/** Two-digit lowercase hex of a byte's unsigned value (replaces `%02x`). */
internal fun Byte.toHex2(): String {
    val v = toInt() and 0xFF
    return v.toString(16).padStart(2, '0')
}

// ── Big-endian readers (replace java.nio.ByteBuffer) ─────────────────────────

/** Reads a big-endian signed int64 starting at [offset]. Caller ensures 8 bytes are available. */
internal fun ByteArray.beLongAt(offset: Int): Long {
    var result = 0L
    for (i in 0 until 8) {
        result = (result shl 8) or (this[offset + i].toLong() and 0xFF)
    }
    return result
}

/** Reads a big-endian signed int16 starting at [offset]. Caller ensures 2 bytes are available. */
internal fun ByteArray.beShortAt(offset: Int): Short {
    val hi = this[offset].toInt() and 0xFF
    val lo = this[offset + 1].toInt() and 0xFF
    return ((hi shl 8) or lo).toShort()
}
