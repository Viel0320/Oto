package com.viel.aplayer.logger

internal object StartupLogger {
    private const val TAG = "Startup"

    fun logWarmupFailure(phase: String, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "warmup failure: phase=$phase, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }
}
