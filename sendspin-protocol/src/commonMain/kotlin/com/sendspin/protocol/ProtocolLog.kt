package com.sendspin.protocol

import kotlin.concurrent.Volatile

/** Severity levels for [SendSpinLogger], mirroring the old Timber/j.u.l facade. */
enum class SendSpinLogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

/**
 * Host-supplied logging sink. The library is multiplatform and cannot depend on any concrete
 * logger; the consumer wires one in via [installSendSpinLogger] (e.g. a Kermit adapter).
 */
fun interface SendSpinLogger {
    fun log(level: SendSpinLogLevel, throwable: Throwable?, message: String)
}

/** Discards everything — the default until a host installs a real logger. */
object NoOpLogger : SendSpinLogger {
    override fun log(level: SendSpinLogLevel, throwable: Throwable?, message: String) = Unit
}

/** Install the process-wide protocol logger. Call once at startup; safe to call again to replace. */
fun installSendSpinLogger(logger: SendSpinLogger) {
    ProtocolLog.delegate = logger
}

/**
 * Timber-compatible logging facade. Protocol source files import this as
 * `import com.sendspin.protocol.ProtocolLog as Timber` so call sites are unchanged.
 *
 * Only `%s` and `%d` placeholders are interpolated (see [formatLog]); the handful of float/hex
 * sites format their argument at the call site (see [toFixed] / [toHex2]) — there is no
 * `String.format` in common Kotlin.
 */
internal object ProtocolLog {
    @Volatile
    var delegate: SendSpinLogger = NoOpLogger

    fun v(message: String, vararg args: Any?) = delegate.log(SendSpinLogLevel.VERBOSE, null, formatLog(message, args))
    fun d(message: String, vararg args: Any?) = delegate.log(SendSpinLogLevel.DEBUG, null, formatLog(message, args))
    fun i(message: String, vararg args: Any?) = delegate.log(SendSpinLogLevel.INFO, null, formatLog(message, args))
    fun w(message: String, vararg args: Any?) = delegate.log(SendSpinLogLevel.WARN, null, formatLog(message, args))
    fun w(t: Throwable?, message: String, vararg args: Any?) = delegate.log(SendSpinLogLevel.WARN, t, formatLog(message, args))
    fun e(message: String, vararg args: Any?) = delegate.log(SendSpinLogLevel.ERROR, null, formatLog(message, args))
    fun e(t: Throwable?, message: String, vararg args: Any?) = delegate.log(SendSpinLogLevel.ERROR, t, formatLog(message, args))
}
