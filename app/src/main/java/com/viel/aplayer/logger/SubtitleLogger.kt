package com.viel.aplayer.logger

import android.util.Log

/**
 * Subtitle Parsing Logger (Track subtitle ingestion and parse results)
 *
 * Consolidates logs from SubtitleParser regarding input ingestion, parse metrics, and file extensions.
 * Uses a unified tag "Subtitle" to simplify diagnosis in Logcat.
 */
internal object SubtitleLogger {

    private const val TAG = "Subtitle"

    /**
     * Log Subtitle Parsing Inception (Record parsing startup events)
     *
     * Captures the initialization of parsing for a given format extension.
     *
     * @param extension The subtitle file extension (e.g., srt, ass, lrc).
     */
    fun logParseStart(extension: String) {
        Log.d(TAG, "Parsing $extension")
    }

    /**
     * Log Subtitle Parsing Outcome (Record summary metrics upon parse completion)
     *
     * Captures the final count of loaded subtitle items.
     *
     * @param lineCount The total number of parsed subtitle lines.
     */
    fun logParseResult(lineCount: Int) {
        Log.d(TAG, "Parsed $lineCount lines")
    }

    /**
     * Log Subtitle Budget Truncation (Record parser-side early exits for oversized subtitle files)
     *
     * Captures the subtitle format that exceeded the bounded cue budget so playback diagnostics can distinguish
     * malformed sidecar files from empty or unsupported subtitle inputs.
     *
     * @param extension The subtitle file extension whose parsed cue stream was truncated.
     */
    fun logSubtitleTruncated(extension: String) {
        // Release Warning Boundary (Sanitize parser-controlled subtitle diagnostics)
        // The extension is low risk today, but routing warnings through SecureLog prevents future parser text from bypassing the policy.
        SecureLog.warn(TAG, "Truncated oversized $extension subtitle")
    }

    /**
     * Log Subtitle Parsing Error: Records parsing failures for specific subtitle formats.
     * Prevents production crash or insecure printStackTrace output by routing errors safely to log.
     */
    fun logParseError(extension: String, error: Throwable) {
        SecureLog.error(TAG, "Failed parsing $extension subtitle", error)
    }
}
