package com.viel.aplayer.logger

/**
 * ABS Authentication Logger (Authentication and server connection detection logs)
 *
 * Boundaries of responsibility:
 * 1. Only logs events related to login, status, authorize, libraries, and credential store.
 * 2. Does not log settings interactions, catalog mirroring, or playback sessions.
 * 3. All log values must be sanitized via AbsLogSanitizer to avoid leaking sensitive information like tokens, passwords, or signed URLs.
 */
internal object AbsAuthLogger {
    private const val TAG = "AbsAuth"

    fun mark(): Long = AbsLogClock.mark()

    fun elapsedMs(startNs: Long): Long = AbsLogClock.elapsedMs(startNs)

    fun logStatusStart(baseUrl: String) {
        AbsLogEmitter.debug(TAG, "status start: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}")
    }

    fun logStatusSuccess(baseUrl: String, costMs: Long, serverVersion: String?) {
        AbsLogEmitter.debug(
            TAG,
            "status success: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}, cost=${costMs}ms, serverVersion=${AbsLogSanitizer.compact(serverVersion, 32)}"
        )
    }

    fun logStatusFailure(baseUrl: String, costMs: Long, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "status failure: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}, cost=${costMs}ms, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logLoginRequestStart(baseUrl: String, username: String) {
        AbsLogEmitter.debug(
            TAG,
            "login start: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}, username=${AbsLogSanitizer.compact(username, 48)}"
        )
    }

    fun logLoginRequestSuccess(baseUrl: String, username: String, costMs: Long, resolvedUsername: String?) {
        AbsLogEmitter.debug(
            TAG,
            "login success: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}, username=${AbsLogSanitizer.compact(username, 48)}, resolvedUsername=${AbsLogSanitizer.compact(resolvedUsername, 48)}, cost=${costMs}ms"
        )
    }

    fun logLoginRequestFailure(baseUrl: String, username: String, costMs: Long, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "login failure: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}, username=${AbsLogSanitizer.compact(username, 48)}, cost=${costMs}ms, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logAuthorizeStart(baseUrl: String) {
        AbsLogEmitter.debug(TAG, "authorize start: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}")
    }

    fun logAuthorizeSuccess(baseUrl: String, costMs: Long, userId: String?) {
        AbsLogEmitter.debug(
            TAG,
            "authorize success: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}, cost=${costMs}ms, userId=${AbsLogSanitizer.shortId(userId)}"
        )
    }

    fun logAuthorizeFailure(baseUrl: String, costMs: Long, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "authorize failure: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}, cost=${costMs}ms, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logLibrariesStart(baseUrl: String) {
        AbsLogEmitter.debug(TAG, "libraries start: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}")
    }

    fun logLibrariesSuccess(baseUrl: String, costMs: Long, total: Int, books: Int) {
        AbsLogEmitter.debug(
            TAG,
            "libraries success: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}, cost=${costMs}ms, total=$total, books=$books"
        )
    }

    fun logLibrariesFailure(baseUrl: String, costMs: Long, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "libraries failure: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}, cost=${costMs}ms, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logCredentialSave(baseUrl: String, credentialId: String, userId: String?, username: String?) {
        AbsLogEmitter.debug(
            TAG,
            "credential save: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}, credentialId=${AbsLogSanitizer.shortId(credentialId)}, userId=${AbsLogSanitizer.shortId(userId)}, username=${AbsLogSanitizer.compact(username, 48)}"
        )
    }

    fun logCredentialGet(credentialId: String?, found: Boolean) {
        AbsLogEmitter.debug(
            TAG,
            "credential get: credentialId=${AbsLogSanitizer.shortId(credentialId)}, found=$found"
        )
    }

    fun logCredentialDelete(credentialId: String?) {
        AbsLogEmitter.debug(
            TAG,
            "credential delete: credentialId=${AbsLogSanitizer.shortId(credentialId)}"
        )
    }

    fun logMissingCredential(path: String, rootId: String, credentialId: String?) {
        AbsLogEmitter.warn(
            TAG,
            "missing credential: path=$path, rootId=${AbsLogSanitizer.shortId(rootId)}, credentialId=${AbsLogSanitizer.shortId(credentialId)}"
        )
    }
}
