package com.viel.oto.event.feedback

import android.content.Context
import com.viel.oto.R
import com.viel.oto.application.library.LibraryReadStatus
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.media.PlaybackSourcePreflightBlockReason

/**
 * Centralizes the Android resource keys used by feedback producers.
 *
 * This app-owned adapter keeps playback and settings feedback names stable while the extracted event
 * module owns only the message carrier and delivery contract.
 */
object FeedbackMessages {
    fun messageSeparator(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_message_separator)

    fun libraryRootsUnavailableNone(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_sync_roots_unavailable_none)

    fun libraryRootsUnavailableSync(rootCount: Int): FeedbackMessage =
        FeedbackMessage.Quantity(R.plurals.feedback_sync_roots_unavailable_multiple, rootCount)

    fun libraryRootUnavailableSync(
        rootName: String,
        availabilityStatus: AudiobookSchema.AvailabilityStatus,
        fallbackCode: String
    ): FeedbackMessage {
        val args = listOf(rootName)
        return when (availabilityStatus) {
            AudiobookSchema.AvailabilityStatus.REVOKED ->
                FeedbackMessage.Resource(R.string.feedback_sync_root_unavailable_revoked, args)
            AudiobookSchema.AvailabilityStatus.AUTH_FAILED ->
                FeedbackMessage.Resource(R.string.feedback_sync_root_unavailable_auth_failed, args)
            AudiobookSchema.AvailabilityStatus.NETWORK_UNAVAILABLE ->
                FeedbackMessage.Resource(R.string.feedback_sync_root_unavailable_network_unavailable, args)
            AudiobookSchema.AvailabilityStatus.TIMEOUT ->
                FeedbackMessage.Resource(R.string.feedback_sync_root_unavailable_timeout, args)
            AudiobookSchema.AvailabilityStatus.NOT_FOUND ->
                FeedbackMessage.Resource(R.string.feedback_sync_root_unavailable_not_found, args)
            AudiobookSchema.AvailabilityStatus.PERMISSION_DENIED ->
                FeedbackMessage.Resource(R.string.feedback_sync_root_unavailable_permission_denied, args)
            AudiobookSchema.AvailabilityStatus.SERVER_ERROR ->
                FeedbackMessage.Resource(R.string.feedback_sync_root_unavailable_server_error, args)
            AudiobookSchema.AvailabilityStatus.UNSUPPORTED ->
                FeedbackMessage.Resource(R.string.feedback_sync_root_unavailable_unsupported, args)
            else ->
                FeedbackMessage.Resource(R.string.feedback_sync_root_unavailable_status_code, listOf(rootName, fallbackCode))
        }
    }

    fun playbackCleartextBlocked(bookTitle: String? = null): FeedbackMessage =
        withStoppedPlaybackScope(
            message = FeedbackMessage.Resource(R.string.feedback_playback_cleartext_blocked),
            bookTitle = bookTitle
        )

    fun playbackInitialMediaLoadFailed(errorMessage: String, bookTitle: String? = null): FeedbackMessage =
        withStoppedPlaybackScope(
            message = FeedbackMessage.Resource(R.string.feedback_playback_initial_media_load_failed, listOf(errorMessage)),
            bookTitle = bookTitle
        )

    fun playbackNoAvailableTrackAfterFailure(bookTitle: String? = null): FeedbackMessage =
        withStoppedPlaybackScope(
            message = FeedbackMessage.Resource(R.string.feedback_playback_no_available_track_after_failure),
            bookTitle = bookTitle
        )

    fun playbackFinishedShutdownScheduled(delaySeconds: Int): FeedbackMessage =
        FeedbackMessage.Quantity(R.plurals.feedback_playback_finished_shutdown_scheduled, delaySeconds)

    fun playbackBookmarkCreated(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_playback_bookmark_created)

    fun playbackRemoteProgressSaveFailed(errorMessage: String?): FeedbackMessage =
        FeedbackMessage.Resource(
            R.string.feedback_playback_remote_progress_save_failed,
            listOf(errorMessage ?: "")
        )

    fun playbackSpeedReset(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_playback_speed_reset)

    fun playbackSpeedChanged(speedText: String): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_playback_speed_changed, listOf(speedText))

    fun playbackSourcePreflightBlocked(
        reason: PlaybackSourcePreflightBlockReason,
        rootName: String?,
        bookTitle: String? = null
    ): FeedbackMessage =
        withStoppedPlaybackScope(
            message = when (reason) {
            PlaybackSourcePreflightBlockReason.MissingRoot ->
                FeedbackMessage.Resource(R.string.feedback_playback_source_preflight_missing_root)
            PlaybackSourcePreflightBlockReason.UnavailableRoot ->
                FeedbackMessage.Resource(
                    R.string.feedback_playback_source_preflight_unavailable_root,
                    listOf(rootName.orEmpty())
                )
            },
            bookTitle = bookTitle
        )

    /**
     * Creates the shared unavailable-track message source.
     *
     * The message carries routing payload for Dialog rendering while still resolving to the existing
     * Toast copy when consumed with Toast render mode.
     */
    fun playbackTrackUnavailable(bookId: String, queueIndex: Int, bookTitle: String? = null): FeedbackMessage =
        FeedbackMessage.PlaybackTrackUnavailable(bookId, queueIndex, bookTitle)

    fun settingsLocalLibraryRelocated(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_settings_local_library_relocated)

    fun settingsLocalLibraryRelocationFailed(errorMessage: String?): FeedbackMessage =
        FeedbackMessage.Resource(
            R.string.feedback_settings_local_library_relocation_failed,
            listOf(errorMessage ?: "")
        )

    fun settingsWebDavUpdated(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_settings_webdav_updated)

    fun settingsWebDavUpdateFailed(errorMessage: String?): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_settings_webdav_update_failed, listOf(errorMessage ?: ""))

    fun settingsWebDavConnectionSucceeded(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_settings_webdav_connection_succeeded)

    fun settingsWebDavConnectionFailed(friendlyMessage: String): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_settings_webdav_connection_failed, listOf(friendlyMessage))

    fun settingsWebDavRootAlreadyExists(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_settings_webdav_root_already_exists)

    fun settingsAbsServerSaved(editing: Boolean): FeedbackMessage =
        FeedbackMessage.Resource(
            if (editing) {
                R.string.feedback_settings_abs_server_updated
            } else {
                R.string.feedback_settings_abs_server_added
            }
        )

    fun settingsAbsServerSaveFailed(redactedMessage: String): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_settings_abs_server_save_failed, listOf(redactedMessage))

    fun settingsAbsConnectionSucceeded(libraryCount: Int): FeedbackMessage =
        FeedbackMessage.Quantity(R.plurals.feedback_settings_abs_connection_succeeded, libraryCount)

    fun settingsAbsConnectionFailed(friendlyMessage: String): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_settings_abs_connection_failed, listOf(friendlyMessage))

    fun settingsAbsRootAlreadyExists(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_settings_abs_root_already_exists)

    fun settingsRootUnavailableSyncBlocked(detailMessage: FeedbackMessage): FeedbackMessage =
        detailMessage

    fun settingsAbsSyncStarted(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_settings_abs_sync_started)

    fun settingsAbsSyncAlreadyRunning(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_settings_abs_sync_already_running)

    fun settingsLibraryRootRemoved(playbackWasStopped: Boolean): FeedbackMessage =
        FeedbackMessage.Resource(
            if (playbackWasStopped) {
                R.string.feedback_settings_library_root_removed_playback_stopped
            } else {
                R.string.feedback_settings_library_root_removed
            }
        )

    fun downloadCacheQueued(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_download_cache_queued)

    fun downloadCachePaused(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_download_cache_paused)

    fun downloadCacheResumed(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_download_cache_resumed)

    fun downloadCacheDeleted(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_download_cache_deleted)

    fun downloadNotificationPermissionDenied(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_download_notification_permission_denied)

    fun downloadCacheCommandFailed(errorMessage: String?): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_download_cache_command_failed, listOf(errorMessage ?: ""))

    fun deletedBookRecoveryRestoredReady(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_deleted_book_recovery_restored_ready)

    fun deletedBookRecoveryRestoredPartial(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_deleted_book_recovery_restored_partial)

    fun homeBookDeleted(sourceFileKept: Boolean): FeedbackMessage =
        FeedbackMessage.Resource(
            if (sourceFileKept) {
                R.string.feedback_home_book_deleted_source_kept
            } else {
                R.string.feedback_home_book_deleted_source_missing
            }
        )

    fun homeReadStatusUpdated(readStatus: LibraryReadStatus): FeedbackMessage =
        FeedbackMessage.Resource(
            when (readStatus) {
                LibraryReadStatus.NOT_STARTED ->
                    R.string.feedback_home_read_status_not_started

                LibraryReadStatus.IN_PROGRESS ->
                    R.string.feedback_home_read_status_in_progress

                LibraryReadStatus.FINISHED ->
                    R.string.feedback_home_read_status_finished
            }
        )

    fun homeCoverMetadataRegenerationStarted(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_home_cover_metadata_regeneration_started)

    fun homeCoverMetadataRegenerationCompleted(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_home_cover_metadata_regeneration_completed)

    fun absBackgroundSyncRootMissing(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_abs_background_sync_root_missing)

    fun absBackgroundSyncUnavailable(detailMessage: FeedbackMessage): FeedbackMessage =
        detailMessage

    fun absBackgroundSyncCompleted(addedBooks: Int, failedItems: Int): FeedbackMessage =
        FeedbackMessage.Composite(
            listOf(
                FeedbackMessage.Resource(R.string.feedback_abs_background_sync_completed),
                FeedbackMessage.Quantity(R.plurals.feedback_abs_background_sync_added_books, addedBooks),
                FeedbackMessage.Resource(R.string.feedback_abs_background_sync_completed_separator),
                FeedbackMessage.Quantity(R.plurals.feedback_abs_background_sync_failed_books, failedItems)
            )
        )

    fun absBackgroundSyncFailed(redactedMessage: String): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_abs_background_sync_failed, listOf(redactedMessage))

    fun scanCompletedWithDiscoveredBooks(discoveredCount: Int): FeedbackMessage =
        FeedbackMessage.Quantity(R.plurals.feedback_scan_completed_with_discovered_books, discoveredCount)

    fun scanCompleted(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_scan_completed)

    fun scanLibraryEmpty(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_scan_library_empty)

    fun scanAlreadyUpToDate(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_scan_already_up_to_date)

    fun scanCompletedSuffixUpdated(updatedCount: Int): FeedbackMessage =
        FeedbackMessage.Quantity(R.plurals.feedback_scan_suffix_updated, updatedCount)

    fun scanCompletedSuffixPartial(partialCount: Int): FeedbackMessage =
        FeedbackMessage.Quantity(R.plurals.feedback_scan_suffix_partial, partialCount)

    /**
     * Access-form-neutral no-work feedback.
     *
     * The scan command may internally distinguish enumerable roots from catalog-backed roots, but the
     * listener only needs to know that no library is currently available for this sync attempt.
     */
    fun scanBlockedNoAvailableLibraries(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_scan_blocked_no_available_libraries)

    fun scanRetryLater(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_scan_retry_later)

    fun scanFailed(errorMessage: String): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_scan_failed, listOf(errorMessage))

    fun sleepMotionTrackingPaused(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_sleep_motion_tracking_paused)

    fun sleepTrackingPausedByActivity(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_sleep_tracking_paused_by_activity)

    fun sleepMotionTrackingResumed(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_sleep_motion_tracking_resumed)

    fun sleepShakeExtendedToNextChapter(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_sleep_shake_extended_to_next_chapter)

    fun sleepShakeNoNextChapter(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_sleep_shake_no_next_chapter)

    fun sleepShakeCountdownReset(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_sleep_shake_countdown_reset)

    fun sleepShakeTestCountdownReset(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_sleep_shake_test_countdown_reset)

    fun sleepTrackingCountdownStarted(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_sleep_tracking_countdown_started)

    fun sleepTimerOff(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_sleep_timer_off)

    fun sleepTimerFiveSeconds(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_sleep_timer_five_seconds)

    fun sleepTimerEndOfChapter(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_sleep_timer_end_of_chapter)

    fun sleepTimerMinutes(minutes: Int): FeedbackMessage =
        FeedbackMessage.Quantity(R.plurals.feedback_sleep_timer_minutes, minutes)

    fun chapterPhysicalFileMissing(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_chapter_physical_file_missing)

    fun settingsExportSuccess(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_settings_export_success)

    fun settingsExportStreamFailed(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_settings_export_stream_failed)

    fun settingsExportFailed(errorMessage: String?): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_settings_export_failed, listOf(errorMessage ?: ""))

    fun settingsImportStreamFailed(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_settings_import_stream_failed)

    fun settingsImportVersionIncompatible(backupVersion: Int, currentVersion: Int): FeedbackMessage =
        FeedbackMessage.Resource(
            R.string.feedback_settings_import_version_incompatible,
            listOf(backupVersion, currentVersion)
        )

    fun settingsImportFailed(errorMessage: String?): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_settings_import_failed, listOf(errorMessage ?: ""))

    /**
     * Appends the affected book title to blocking playback dialogs.
     *
     * Dialog feedback often reports a source or policy failure, but the user also needs the playback
     * impact. Keeping the scope append inside the message factory preserves a single renderable message
     * source for both generic dialogs and specialized recovery dialogs.
     */
    private fun withStoppedPlaybackScope(message: FeedbackMessage, bookTitle: String?): FeedbackMessage {
        val scopedTitle = bookTitle?.trim().orEmpty()
        if (scopedTitle.isBlank()) return message
        return FeedbackMessage.Composite(
            listOf(
                message,
                FeedbackMessage.Resource(R.string.feedback_playback_stopped_scope, listOf(scopedTitle))
            )
        )
    }

    /**
     * Formats the affected book title for semantic playback messages.
     *
     * Specialized messages such as track-unavailable must remain routeable by type, so they cannot be
     * wrapped in [FeedbackMessage.Composite]. Rendering uses the same scope resource as generic dialog
     * feedback to keep copy consistent.
     */
    fun renderStoppedPlaybackScope(message: String, bookTitle: String?, context: Context): String {
        val scopedTitle = bookTitle?.trim().orEmpty()
        if (scopedTitle.isBlank()) return message
        return message + context.getString(R.string.feedback_playback_stopped_scope, scopedTitle)
    }
}

/**
 * Resolves resource-backed feedback at the app-shell edge.
 *
 * Rendering is intentionally outside event producers so media, workers, and ViewModels emit facts rather
 * than localized render text.
 */
fun FeedbackMessage.render(context: Context): String =
    when (this) {
        is FeedbackMessage.Resource -> context.getString(resId, *args.toTypedArray())
        is FeedbackMessage.Quantity -> context.resources.getQuantityString(resId, quantity, *args.toTypedArray())
        is FeedbackMessage.Composite -> parts.joinToString(separator = "") { part -> part.render(context) }
        is FeedbackMessage.PlaybackTrackUnavailable -> FeedbackMessages.renderStoppedPlaybackScope(
            message = context.getString(R.string.feedback_playback_track_unavailable),
            bookTitle = bookTitle,
            context = context
        )
    }
