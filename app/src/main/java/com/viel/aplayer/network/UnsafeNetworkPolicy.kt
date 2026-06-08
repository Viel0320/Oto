package com.viel.aplayer.network

import com.viel.aplayer.data.store.AppSettings

/**
 * Unsafe Network Policy (Centralizes user-controlled insecure transport decisions)
 *
 * Keeps cleartext HTTP and insecure TLS bypass rules in one pure policy so WebDAV, ABS, and
 * playback preflight code cannot drift into provider-specific defaults.
 */
object UnsafeNetworkPolicy {
    /**
     * Cleartext URL Detection (Identifies unencrypted HTTP endpoints)
     *
     * Uses scheme-level inspection only, allowing callers to pass normalized roots, resolved
     * request URLs, or test server URLs without coupling this policy to Android Uri or OkHttp.
     */
    fun isCleartextHttp(url: String): Boolean =
        url.trimStart().startsWith("http://", ignoreCase = true)

    /**
     * Cleartext Permission Check (Applies the global HTTP opt-in switch)
     *
     * Defaults to blocking HTTP unless the user explicitly enabled cleartext traffic in settings.
     */
    fun isCleartextHttpAllowed(url: String, settings: AppSettings): Boolean =
        !isCleartextHttp(url) || settings.isCleartextTrafficAllowed

    /**
     * Cleartext Guard (Stops insecure HTTP before credentials or media requests are sent)
     *
     * Throws a structured policy violation so infrastructure adapters can translate the block into
     * their own domain errors without duplicating the decision rule.
     */
    fun requireCleartextHttpAllowed(url: String, settings: AppSettings, operation: String) {
        if (!isCleartextHttpAllowed(url, settings)) {
            throw UnsafeNetworkPolicyViolation(
                kind = UnsafeNetworkViolationKind.CleartextHttp,
                operation = operation,
                message = "UNSAFE_NETWORK_CLEARTEXT_HTTP_BLOCKED"
            )
        }
    }

    /**
     * Insecure TLS Permission Check (Applies the global certificate-bypass opt-in switch)
     *
     * No root-level or credential-level TLS exception is considered valid; the global settings
     * switch is the single source of truth for self-signed or otherwise untrusted certificates.
     */
    fun isInsecureTlsAllowed(settings: AppSettings): Boolean =
        settings.isAllowInsecureTls
}

/**
 * Unsafe Network Violation Kind (Classifies blocked insecure transport attempts)
 *
 * Gives callers a stable branch key instead of parsing localized exception messages.
 */
enum class UnsafeNetworkViolationKind {
    CleartextHttp
}

/**
 * Unsafe Network Policy Violation (Signals that a network request violates user settings)
 *
 * Carries the operation label and violation kind so WebDAV, ABS, and playback layers can map the
 * same policy failure into their own logging, availability, or feedback paths.
 */
class UnsafeNetworkPolicyViolation(
    val kind: UnsafeNetworkViolationKind,
    val operation: String,
    message: String
) : IllegalStateException(message)
