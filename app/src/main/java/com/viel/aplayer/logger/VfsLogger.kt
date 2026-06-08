package com.viel.aplayer.logger

import android.os.SystemClock
import android.util.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * VFS File I/O Performance Logger (Track latency and outcomes of VFS operations)
 *
 * Provides coverage for both SAF (SafSourceProvider) and WebDav (WebDavSourceProvider) layers,
 * logging file opens, seek operations, range queries, PROPFIND folder walking, and network errors.
 * Uses a unified tag "VfsIO" to streamline VFS diagnosing in Logcat.
 */
internal object VfsLogger {

    private const val TAG = "VfsIO"
    // Path Length Constraint (Maximum characters displayed to prevent Logcat flooding)
    private const val MAX_PATH_LENGTH = 120

    // Timing Inception Marker (Log elapsed durations safely independent of system clock drifts)
    fun mark(): Long = SystemClock.elapsedRealtime()

    // Calculate Elapsed Milliseconds (Determine time delta from a starting timestamp)
    fun elapsedMs(startMs: Long): Long = SystemClock.elapsedRealtime() - startMs

    // ========== SAF ==========

    /**
     * Log SAF Input Stream Open (Record execution duration and status for opening SAF streams)
     *
     * Tracks raw input stream opening events under SAF.
     *
     * @param path The virtual file sourcePath.
     * @param offset The seek offset (0 represents sequential reading).
     * @param costMs Time spent in milliseconds.
     * @param success True if the stream opened successfully, false otherwise.
     * @param error The simple name of the exception if failed (optional).
     */
    fun logSafOpen(path: String, offset: Long, costMs: Long, success: Boolean, error: String? = null) {
        val errorSuffix = error?.let { ", error=$it" }.orEmpty()
        Log.d(
            TAG,
            "SAF openInputStream(path=${compact(path)}, offset=$offset) " +
                "cost=${costMs}ms, success=$success$errorSuffix"
        )
    }

    /**
     * Log SAF File Descriptor Access (Record execution duration and status for opening FD)
     *
     * Tracks the file descriptor open latency for random-access playback optimization.
     *
     * @param path The virtual file sourcePath.
     * @param costMs Time spent in milliseconds.
     * @param success True if the FD was accessed successfully, false otherwise.
     */
    fun logSafOpenFd(path: String, costMs: Long, success: Boolean) {
        Log.d(
            TAG,
            "SAF openFileDescriptor(path=${compact(path)}) cost=${costMs}ms, success=$success"
        )
    }

    // ========== WebDAV ==========

    /**
     * Log WebDAV PROPFIND Request (Record latency and size of remote folder enum queries)
     *
     * Monitors PROPFIND network and XML parsing latency.
     *
     * @param sourcePath The target remote folder sourcePath.
     * @param depth The PROPFIND request depth header ("0" or "1").
     * @param costMs Time spent in milliseconds.
     * @param resourceCount The number of files/subfolders returned.
     */
    fun logWebDavPropfind(sourcePath: String, depth: String, costMs: Long, resourceCount: Int) {
        Log.d(
            TAG,
            "WebDAV PROPFIND(path=${compact(sourcePath)}, depth=$depth) " +
                "cost=${costMs}ms, resources=$resourceCount"
        )
    }

    /**
     * Log WebDAV Stream Request (Record latency and HTTP code for stream requests)
     *
     * Monitors remote network stream opening events.
     *
     * @param sourcePath The virtual file sourcePath.
     * @param offset The seek offset mapping to Range request headers.
     * @param costMs Time spent in milliseconds.
     * @param httpCode The server response code.
     * @param success True if the connection successfully established, false otherwise.
     */
    fun logWebDavOpen(sourcePath: String, offset: Long, costMs: Long, httpCode: Int, success: Boolean) {
        Log.d(
            TAG,
            "WebDAV GET(path=${compact(sourcePath)}, offset=$offset) " +
                "cost=${costMs}ms, http=$httpCode, success=$success"
        )
    }

    /**
     * Log WebDAV Range Read (Record performance of small segment block reads)
     *
     * Useful for diagnostics on metadata parsing requests.
     *
     * @param sourcePath The virtual file sourcePath.
     * @param offset The seek offset.
     * @param requestedLength The requested byte count.
     * @param costMs Time spent in milliseconds.
     * @param actualBytes The actual bytes read (null indicates failure).
     */
    fun logWebDavRange(sourcePath: String, offset: Long, requestedLength: Int, costMs: Long, actualBytes: Int?) {
        Log.d(
            TAG,
            "WebDAV Range(path=${compact(sourcePath)}, offset=$offset, len=$requestedLength) " +
                "cost=${costMs}ms, actual=${actualBytes ?: "null"}"
        )
    }

    /**
     * Log WebDAV Network Failures (Record remote network request exceptions)
     *
     * Traces timeouts, socket errors, and maps them to a unified availability status.
     *
     * @param url The request target URL.
     * @param status The mapped availability code.
     * @param errorClass The name of the thrown exception class.
     */
    fun logWebDavError(url: String, status: String, errorClass: String) {
        Log.d(
            TAG,
            buildWebDavErrorMessage(url = url, status = status, errorClass = errorClass)
        )
    }

    /**
     * Build WebDAV Error Message (Format network failures without leaking endpoint credentials)
     *
     * The pure builder keeps URL redaction testable on the JVM before the final Logcat write happens.
     */
    fun buildWebDavErrorMessage(url: String, status: String, errorClass: String): String =
        "WebDAV error url=${compactWebDavUrl(url)}, status=$status, exception=$errorClass"

    /**
     * WebDAV Log URL Sanitizer (Remove username, password, query, and fragment from diagnostic URLs)
     *
     * WebDAV request URLs may originate from user input or OkHttp request objects, so diagnostics retain only scheme, host, port, and path.
     */
    private fun compactWebDavUrl(value: String): String =
        compact(value.toHttpUrlOrNull()?.redactedForLog() ?: stripUrlTail(value))

    /**
     * WebDAV Fallback URL Tail Strip (Drop query and fragment from non-OkHttp-parsable text)
     *
     * Invalid URL strings should still lose common secret-bearing suffixes before length compaction is applied.
     */
    private fun stripUrlTail(value: String): String =
        value.substringBefore('#').substringBefore('?')

    /**
     * WebDAV Log Sanitizer (Remove username, password, query, and fragment from diagnostic URLs)
     *
     * Clearing OkHttp's username and password fields prevents authority userinfo from surviving in exception and timeout diagnostics.
     */
    private fun HttpUrl.redactedForLog(): String =
        newBuilder().username("").password("").query(null).fragment(null).build().toString()

    /**
     * Shorten Log Identifiers (Truncate overly long path and URL logs for readability)
     *
     * Limits text length to ensure clean Logcat formats.
     */
    private fun compact(value: String): String {
        return if (value.length <= MAX_PATH_LENGTH) {
            value
        } else {
            "${value.take(MAX_PATH_LENGTH)}..."
        }
    }
}
