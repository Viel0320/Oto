package com.viel.oto.logger

import com.viel.oto.data.db.AudiobookSchema

/**
 * User actions and settings interface logging.
 *
 * Boundaries of responsibility:
 * 1. Only logs user-triggered events like test connections, adding servers, manual sync requests, scheduling background syncs, and deleting servers.
 * 2. Does not directly log low-level REST requests, mirror synchronizer updates, or playback stream events.
 * 3. Bridges user operations to low-level sync engines for general troubleshooting.
 */
internal object AbsSettingsLogger {
    private const val TAG = "AbsSettings"

    fun mark(): Long = AbsLogClock.mark()

    fun elapsedMs(startNs: Long): Long = AbsLogClock.elapsedMs(startNs)

    fun logTestConnectionStart(baseUrl: String, username: String) {
        AbsLogEmitter.debug(
            TAG,
            "testConnection start: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}, username=${AbsLogSanitizer.compact(username, 48)}"
        )
    }

    fun logTestConnectionSuccess(baseUrl: String, username: String, costMs: Long, libraryCount: Int, serverVersion: String?) {
        AbsLogEmitter.debug(
            TAG,
            "testConnection success: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}, username=${AbsLogSanitizer.compact(username, 48)}, cost=${costMs}ms, libraries=$libraryCount, serverVersion=${AbsLogSanitizer.compact(serverVersion, 32)}"
        )
    }

    fun logTestConnectionFailure(baseUrl: String, username: String, costMs: Long, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "testConnection failure: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}, username=${AbsLogSanitizer.compact(username, 48)}, cost=${costMs}ms, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logAddServerStart(baseUrl: String, username: String, libraryId: String, libraryName: String) {
        AbsLogEmitter.debug(
            TAG,
            "addServer start: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}, username=${AbsLogSanitizer.compact(username, 48)}, libraryId=${AbsLogSanitizer.shortId(libraryId)}, libraryName=${AbsLogSanitizer.compact(libraryName, 64)}"
        )
    }

    fun logAddServerSuccess(baseUrl: String, username: String, libraryId: String, rootId: String?) {
        AbsLogEmitter.debug(
            TAG,
            "addServer success: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}, username=${AbsLogSanitizer.compact(username, 48)}, libraryId=${AbsLogSanitizer.shortId(libraryId)}, rootId=${AbsLogSanitizer.shortId(rootId)}"
        )
    }

    fun logAddServerFailure(baseUrl: String, username: String, libraryId: String, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "addServer failure: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}, username=${AbsLogSanitizer.compact(username, 48)}, libraryId=${AbsLogSanitizer.shortId(libraryId)}, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logManualSyncStart(rootId: String, displayName: String) {
        AbsLogEmitter.debug(
            TAG,
            "manualSync start: rootId=${AbsLogSanitizer.shortId(rootId)}, displayName=${AbsLogSanitizer.compact(displayName, 64)}"
        )
    }

    fun logManualSyncRequiresConfirmation(rootId: String, totalItems: Int) {
        AbsLogEmitter.debug(
            TAG,
            "manualSync requiresConfirmation: rootId=${AbsLogSanitizer.shortId(rootId)}, totalItems=$totalItems"
        )
    }

    fun logManualSyncFinished(rootId: String, costMs: Long) {
        AbsLogEmitter.debug(
            TAG,
            "manualSync success: rootId=${AbsLogSanitizer.shortId(rootId)}, cost=${costMs}ms"
        )
    }

    fun logManualSyncFailure(rootId: String, costMs: Long, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "manualSync failure: rootId=${AbsLogSanitizer.shortId(rootId)}, cost=${costMs}ms, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logScheduleBackgroundSync(rootId: String) {
        AbsLogEmitter.debug(
            TAG,
            "backgroundSync enqueue: rootId=${AbsLogSanitizer.shortId(rootId)}"
        )
    }

    fun logDeleteServerStart(rootId: String, sourceType: AudiobookSchema.LibrarySourceType) {
        AbsLogEmitter.debug(
            TAG,
            "deleteServer start: rootId=${AbsLogSanitizer.shortId(rootId)}, sourceType=${AbsLogSanitizer.compact(sourceType.name, 24)}"
        )
    }

    fun logDeleteServerFinished(rootId: String, playbackStopped: Boolean) {
        AbsLogEmitter.debug(
            TAG,
            "deleteServer success: rootId=${AbsLogSanitizer.shortId(rootId)}, playbackStopped=$playbackStopped"
        )
    }

    fun logDeleteServerFailure(rootId: String, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "deleteServer failure: rootId=${AbsLogSanitizer.shortId(rootId)}, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }
}
