package com.viel.aplayer.logger

import android.util.Log
import java.security.MessageDigest

/**
 * Cache Diagnostics Logger (Aggregates cache events under one sanitized Logcat tag)
 * Emits compact cache facts for directory listings, range reads, cover decodes, and ABS mirror reuse without replacing
 * domain-specific loggers or printing tokens, complete paths, or complete URLs.
 */
object CacheDiagnosticsLogger {
    private const val TAG = "APlayerCache"
    private val allowedCacheTypes = setOf("directory", "range", "cover", "abs_mirror")

    /**
     * Log Cache Event (Writes a normalized cache operation record)
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
        // Safe Logcat Emission (Keeps JVM unit tests from failing on unmocked Android Log APIs)
        // Cache diagnostics are observational only, so a Logcat runtime mismatch must not change ABS sync or directory cache behavior.
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
     * Build Log Message (Formats cache diagnostics without touching Android Logcat)
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
     * Hash Identifier (Converts roots, paths, and catalog identifiers into short non-reversible labels)
     * Lets cache event callers correlate repeated operations while keeping raw provider coordinates out of logs.
     */
    fun hashIdentifier(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val normalized = value.trim().replace('\\', '/')
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        return digest.take(4).joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun sanitizeDetail(value: String?): String {
        // Redact bearer tokens, passwords, and sensitive params using unified AbsLogSanitizer, then genericize remaining URLs.
        val redacted = AbsLogSanitizer.sanitizeText(value ?: "none")
            .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "<url>")
        return compact(redacted)
    }

    private fun compact(value: String): String =
        value.take(MAX_DETAIL_LENGTH)

    private const val MAX_DETAIL_LENGTH = 96
}
