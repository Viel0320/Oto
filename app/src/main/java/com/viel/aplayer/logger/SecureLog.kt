package com.viel.aplayer.logger

import android.util.Log
import java.security.MessageDigest

/**
 * Sanitize warning and error diagnostics retained by release builds.
 *
 * Release R8 rules intentionally keep Log.w and Log.e for field triage, so this boundary removes filesystem paths,
 * VFS coordinates, URL userinfo, bearer tokens, and password-like fields before the message or throwable reaches Logcat.
 */
internal object SecureLog {
    private val windowsAbsolutePathRegex = Regex("[A-Za-z]:\\\\[^\\s,;)\"']+")
    private val androidAbsolutePathRegex =
        Regex("(?<![A-Za-z0-9:])/(?:storage|sdcard|mnt|data|cache|Android|Download|Documents|Pictures|Music|Movies)[^\\s,;)\"']*")
    private val fileUriRegex = Regex("file://[^\\s,;)\"']+", RegexOption.IGNORE_CASE)
    private val vfsSourcePathRegex = Regex("\\b[A-Za-z0-9_-]+:/(?!/)[^\\s,;)\"']+")
    private val urlUserInfoRegex = Regex("(https?://)[^/@\\s]+@", RegexOption.IGNORE_CASE)
    private val secretFieldRegex =
        Regex("\\b(password|passwd|pwd|token|access_token|refresh_token|api[_-]?key|secret)\\s*[:=]\\s*[\"']?[^,\\s\"'}]+[\"']?", RegexOption.IGNORE_CASE)
    private val keyedCoordinateRegex =
        Regex("\\b(sourcePath|sourceId|path|absolutePath|uri)=([^,\\s)]+)", RegexOption.IGNORE_CASE)

    fun warn(tag: String, message: String, error: Throwable? = null) {
        val sanitizedMessage = sanitizeDiagnosticText(message)
        val sanitizedError = error?.let(::sanitizeThrowable)
        runCatching {
            if (sanitizedError == null) {
                Log.w(tag, sanitizedMessage)
            } else {
                Log.w(tag, sanitizedMessage, sanitizedError)
            }
        }
    }

    fun error(tag: String, message: String, error: Throwable? = null) {
        val sanitizedMessage = sanitizeDiagnosticText(message)
        val sanitizedError = error?.let(::sanitizeThrowable)
        runCatching {
            if (sanitizedError == null) {
                Log.e(tag, sanitizedMessage)
            } else {
                Log.e(tag, sanitizedMessage, sanitizedError)
            }
        }
    }

    /**
     * Final scrub before release-retained Logcat writes.
     *
     * This method first reuses the ABS sanitizer, then removes non-ABS path forms and hashes keyed coordinates so callers
     * retain correlation without exposing user-owned filenames, directories, content URIs, or provider source identifiers.
     */
    internal fun sanitizeDiagnosticText(raw: String?): String {
        return AbsLogSanitizer.sanitizeText(raw)
            .replace(urlUserInfoRegex, "$1")
            .replace(secretFieldRegex) { match -> "${match.groupValues[1]}=<redacted>" }
            .replace(keyedCoordinateRegex) { match ->
                "${match.groupValues[1]}=<redacted:${shortHash(match.groupValues[2])}>"
            }
            .replace(fileUriRegex, "<path>")
            .replace(windowsAbsolutePathRegex, "<path>")
            .replace(androidAbsolutePathRegex, "<path>")
            .replace(vfsSourcePathRegex, "<path>")
    }

    /**
     * Preserve failure type and stack shape without preserving sensitive exception text.
     *
     * Android Log prints Throwable.toString and nested causes, so this wrapper copies the stack trace while replacing every
     * throwable message and suppressed exception with sanitized equivalents before the retained log call is emitted.
     */
    internal fun sanitizeThrowable(error: Throwable): Throwable {
        val sanitized = SanitizedThrowable(
            originalType = error::class.java.name,
            sanitizedMessage = sanitizeDiagnosticText(error.message),
            sanitizedCause = error.cause?.let(::sanitizeThrowable)
        )
        sanitized.stackTrace = error.stackTrace
        error.suppressed.forEach { suppressed -> sanitized.addSuppressed(sanitizeThrowable(suppressed)) }
        return sanitized
    }

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(4).joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private class SanitizedThrowable(
        private val originalType: String,
        sanitizedMessage: String,
        sanitizedCause: Throwable?
    ) : Throwable(sanitizedMessage, sanitizedCause) {
        override fun toString(): String {
            val sanitizedMessage = message
            return if (sanitizedMessage.isNullOrBlank()) {
                originalType
            } else {
                "$originalType: $sanitizedMessage"
            }
        }
    }
}
