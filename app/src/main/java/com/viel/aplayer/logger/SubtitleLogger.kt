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
}
