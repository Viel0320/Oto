package com.viel.oto.logger

/**
 * Records startup warmup failures without coupling startup orchestration to app internals.
 *
 * The application still decides which warmup phases run; this module-level logger keeps failure
 * messages sanitized and available to the app shell after observability is extracted from `:app`.
 */
object StartupLogger {
    private const val TAG = "Startup"

    fun logWarmupFailure(phase: String, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "warmup failure: phase=$phase, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }
}
