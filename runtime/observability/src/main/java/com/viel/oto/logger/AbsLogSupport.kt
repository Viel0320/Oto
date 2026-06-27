package com.viel.oto.logger

import android.util.Log

/**
 * Base components for ABS logging support.
 *
 * Design Goals:
 * 1. Ensure all ABS loggers share the same clock timing and sanitization logic to keep logging formats consistent.
 * 2. Redact sensitive values like authorization tokens, API keys, signatures, passwords, Bearer headers, and query parameters before writing to Logcat.
 * 3. Compact excessively long URLs, source paths, and item IDs to avoid flooding Logcat.
 */
internal object AbsLogClock {
    fun mark(): Long = System.nanoTime()

    fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000L
}

/**
 * Utility to redact sensitive information in log text.
 *
 * Uses pure string operations without relying on Android runtime classes to facilitate standard JVM unit testing for credential leaks.
 * The same sensitive key set is applied to JSON-like fields and query-like key-value fragments so debug logs are no narrower than release diagnostics.
 */
object AbsLogSanitizer {
    private const val SENSITIVE_KEY_PATTERN =
        "token|access_token|refresh_token|api[_-]?key|apikey|password|passwd|pwd|secret|sig|signature"

    private val bearerRegex = Regex("Bearer\\s+\\S+", RegexOption.IGNORE_CASE)
    private val authorizationRegex = Regex("(Authorization\\s*[:=]\\s*Bearer\\s+)(\\S+)", RegexOption.IGNORE_CASE)
    private val sensitiveJsonRegex =
        Regex("\"($SENSITIVE_KEY_PATTERN)\"\\s*:\\s*(\"(?:\\\\.|[^\"])*\"|[+-]?\\d+(?:\\.\\d+)?|true|false|null)", RegexOption.IGNORE_CASE)
    private val sensitiveKeyValueRegex =
        Regex("\\b($SENSITIVE_KEY_PATTERN)(\\s*[=:]\\s*)([^&#\\s,;)}\\]\"']+)", RegexOption.IGNORE_CASE)
    private val embeddedHttpUrlRegex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

    fun sanitizeText(raw: String?): String {
        val value = raw.orEmpty()
        return value
            .replace(embeddedHttpUrlRegex) { matchResult -> stripEmbeddedUrlSecrets(matchResult.value) }
            .replace(bearerRegex, "Bearer <redacted>")
            .replace(sensitiveJsonRegex) { matchResult ->
                "\"${matchResult.groupValues[1].lowercase()}\":\"<redacted>\""
            }
            .replace(authorizationRegex, "$1<redacted>")
            .replace(sensitiveKeyValueRegex) { matchResult ->
                "${matchResult.groupValues[1]}${matchResult.groupValues[2]}<redacted>"
            }
    }

    /**
     * Remove query and fragment sections before sanitizing and compacting URLs.
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
     * Removes query and fragment values from exception text URLs.
     * Exception messages can contain full request URLs outside dedicated URL fields, so the general text sanitizer drops secret-bearing URL tails before token-specific redaction runs.
     */
    private fun stripEmbeddedUrlSecrets(value: String): String =
        value.substringBefore('#').substringBefore('?')
}

/**
 * Unified output emitter routing messages to Logcat.
 *
 * Performs a final sanitization sweep on all messages before emitting them to ensure sensitive values are redacted even if callers forgot to sanitize inputs.
 */
internal object AbsLogEmitter {
    fun debug(tag: String, message: String) {
        runCatching { Log.d(tag, AbsLogSanitizer.sanitizeText(message)) }
    }

    fun warn(tag: String, message: String) {
        SecureLog.warn(tag, message)
    }
}
