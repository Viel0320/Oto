package com.viel.oto.logger

import android.util.Log
import java.security.MessageDigest

/**
 * Aggregates cache events under one sanitized Logcat tag.
 * Emits compact cache facts for directory listings, range reads, cover decodes, and ABS mirror reuse without replacing
 * domain-specific loggers or printing tokens, complete paths, or complete URLs.
 */
object CacheDiagnosticsLogger {
    private const val TAG = "OtoCache"
    private val allowedCacheTypes = setOf("directory", "range", "cover", "abs_mirror")

    /**
     * Writes a normalized cache operation record.
     * Accepts pre-hashed source identifiers and sanitized detail text so callers can correlate cache behavior without leaking
     * provider URLs, local filesystem paths, or authorization tokens.
     */
    fun logCacheEvent(
        cacheType: String,
        operation: String,
        hit: Boolean?,
        costMs: Long?,
        sourceHash: String?,
        sizeBytes: Long?,
        detail: String? = null
    ) {
        runCatching {
            Log.d(
                TAG,
                buildLogMessage(
                    cacheType = cacheType,
                    operation = operation,
                    hit = hit,
                    costMs = costMs,
                    sourceHash = sourceHash,
                    sizeBytes = sizeBytes,
                    detail = detail
                )
            )
        }
    }

    /**
     * Formats cache diagnostics without touching Android Logcat.
     * Keeps cache-event normalization testable on the JVM so redaction and field layout stay protected without requiring
     * a device-side Logcat assertion.
     */
    internal fun buildLogMessage(
        cacheType: String,
        operation: String,
        hit: Boolean?,
        costMs: Long?,
        sourceHash: String?,
        sizeBytes: Long?,
        detail: String? = null
    ): String {
        val normalizedType = cacheType.takeIf { it in allowedCacheTypes } ?: "unknown"
        return "cacheType=$normalizedType, operation=${compact(operation)}, hit=${hit?.toString() ?: "n/a"}, " +
            "costMs=${costMs ?: -1L}, sourceHash=${sourceHash ?: "none"}, sizeBytes=${sizeBytes ?: -1L}, " +
            "detail=${sanitizeDetail(detail)}"
    }

    /**
     * Converts roots, paths, and catalog identifiers into short non-reversible labels.
     * Lets cache event callers correlate repeated operations while keeping raw provider coordinates out of logs.
     */
    fun hashIdentifier(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val normalized = value.trim().replace('\\', '/')
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        return digest.take(4).joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun sanitizeDetail(value: String?): String {
        val redacted = AbsLogSanitizer.sanitizeText(value ?: "none")
            .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "<url>")
        return compact(redacted)
    }

    private fun compact(value: String): String =
        value.take(MAX_DETAIL_LENGTH)

    private const val MAX_DETAIL_LENGTH = 96
}
