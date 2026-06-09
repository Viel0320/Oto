package com.viel.aplayer.logger

import android.util.Log

/**
 * ABS Logging Common Base (Base components for ABS logging support)
 *
 * Design Goals:
 * 1. Ensure all ABS loggers share the same clock timing and sanitization logic to keep logging formats consistent.
 * 2. Redact sensitive values like authorization tokens, passwords, Bearer headers, and query parameters before writing to Logcat.
 * 3. Compact excessively long URLs, source paths, and item IDs to avoid flooding Logcat.
 */
internal object AbsLogClock {
    fun mark(): Long = System.nanoTime()

    fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000L
}

/**
 * ABS Log Sanitizer (Utility to redact sensitive information in log text)
 *
 * Uses pure string operations without relying on Android runtime classes to facilitate standard JVM unit testing for credential leaks.
 */
internal object AbsLogSanitizer {
    private val bearerRegex = Regex("Bearer\\s+\\S+", RegexOption.IGNORE_CASE)
    private val tokenJsonRegex = Regex("\"token\"\\s*:\\s*\"[^\"]*\"", RegexOption.IGNORE_CASE)
    private val passwordJsonRegex = Regex("\"password\"\\s*:\\s*\"[^\"]*\"", RegexOption.IGNORE_CASE)
    private val authorizationRegex = Regex("(Authorization\\s*[:=]\\s*Bearer\\s+)(\\S+)", RegexOption.IGNORE_CASE)
    private val tokenQueryRegex = Regex("((?:token|password)=)([^&#\\s]+)", RegexOption.IGNORE_CASE)
    // Embedded URL Detection Pattern (Finds full HTTP URLs inside free-form exception messages)
    // Dedicated URL fields already call sanitizeUrl; this fallback catches URLs that arrive inside mapper, parser, or transport error text.
    private val embeddedHttpUrlRegex = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)

    fun sanitizeText(raw: String?): String {
        val value = raw.orEmpty()
        return value
            .replace(embeddedHttpUrlRegex) { matchResult -> stripEmbeddedUrlSecrets(matchResult.value) }
            .replace(bearerRegex, "Bearer <redacted>")
            .replace(tokenJsonRegex, "\"token\":\"<redacted>\"")
            .replace(passwordJsonRegex, "\"password\":\"<redacted>\"")
            .replace(authorizationRegex, "$1<redacted>")
            .replace(tokenQueryRegex, "$1<redacted>")
    }

    /**
     * URL Sanitization (Remove query and fragment sections before sanitizing and compacting URLs)
     * This keeps the request path needed for debugging while ensuring signature tokens do not slip into logs.
     */
    fun sanitizeUrl(raw: String?): String =
        compact(
            sanitizeText(
                raw.orEmpty()
                    .substringBefore('#')
                    .substringBefore('?')
            )
        )

    fun compact(raw: String?, maxLength: Int = 160): String {
        val value = sanitizeText(raw).ifBlank { "<empty>" }
        return if (value.length <= maxLength) value else "${value.take(maxLength)}..."
    }

    fun shortId(raw: String?, maxLength: Int = 48): String = compact(raw, maxLength)

    /**
     * Embedded URL Secret Stripping (Removes query and fragment values from exception text URLs)
     * Exception messages can contain full request URLs outside dedicated URL fields, so the general text sanitizer drops secret-bearing URL tails before token-specific redaction runs.
     */
    private fun stripEmbeddedUrlSecrets(value: String): String =
        value.substringBefore('#').substringBefore('?')
}

/**
 * ABS Log Emitter (Unified output emitter routing messages to Logcat)
 *
 * Performs a final sanitization sweep on all messages before emitting them to ensure sensitive values are redacted even if callers forgot to sanitize inputs.
 */
internal object AbsLogEmitter {
    fun debug(tag: String, message: String) {
        runCatching { Log.d(tag, AbsLogSanitizer.sanitizeText(message)) }
    }

    fun warn(tag: String, message: String) {
        runCatching { Log.w(tag, AbsLogSanitizer.sanitizeText(message)) }
    }
}
