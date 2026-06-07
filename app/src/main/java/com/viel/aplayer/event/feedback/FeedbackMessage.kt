package com.viel.aplayer.event.feedback

import android.content.Context
import androidx.annotation.StringRes
import com.viel.aplayer.R

/**
 * Feedback Message (Resource-backed transient copy contract)
 *
 * Callers emit a stable message key plus formatting arguments, while Android resource resolution stays
 * in the app-shell renderer where localization belongs.
 */
sealed interface FeedbackMessage {
    val mergeKey: String

    /**
     * Resource Message (Defers localized text lookup to the rendering layer)
     *
     * The resource ID is the stable message key and the argument list contains only primitive display
     * values so tests can assert feedback facts without depending on localized strings.
     */
    data class Resource(
        @StringRes val resId: Int,
        val args: List<Any> = emptyList()
    ) : FeedbackMessage {
        override val mergeKey: String = buildString {
            append("res:")
            append(resId)
            args.forEach { arg ->
                append(':')
                append(arg)
            }
        }
    }

    /**
     * Raw Text Message (Compatibility path for callers not yet migrated to resource keys)
     *
     * Keeping this explicit marker prevents legacy text from looking like localized feedback and makes
     * remaining migration work searchable.
     */
    data class RawText(val value: String) : FeedbackMessage {
        override val mergeKey: String = "raw:$value"
    }

    /**
     * Composite Message (Combines localized fragments into one transient feedback fact)
     *
     * Scan summaries can keep each phrase resource-backed while still emitting a single Toast command.
     */
    data class Composite(val parts: List<FeedbackMessage>) : FeedbackMessage {
        override val mergeKey: String = parts.joinToString(separator = "|") { part -> part.mergeKey }
    }
}

/**
 * Feedback Message Factory (Centralizes the resource keys used by transient feedback callers)
 *
 * This object keeps playback and settings feedback names stable while allowing the app shell to render
 * the final localized text elsewhere.
 */
object FeedbackMessages {
    fun rawText(value: String): FeedbackMessage = FeedbackMessage.RawText(value)

    fun playbackCleartextBlocked(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_playback_cleartext_blocked)

    fun playbackInitialMediaLoadFailed(errorMessage: String): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_playback_initial_media_load_failed, listOf(errorMessage))

    fun playbackNoAvailableTrackAfterFailure(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_playback_no_available_track_after_failure)

    fun playbackFinishedShutdownScheduled(delaySeconds: Int): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_playback_finished_shutdown_scheduled, listOf(delaySeconds))

    fun playbackBookmarkCreated(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_playback_bookmark_created)

    fun playbackSourcePreflightBlocked(message: String): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_playback_source_preflight_blocked, listOf(message))

    fun playbackTrackUnavailable(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_playback_track_unavailable)

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
        FeedbackMessage.Resource(R.string.feedback_settings_abs_connection_succeeded, listOf(libraryCount))

    fun settingsAbsConnectionFailed(friendlyMessage: String): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_settings_abs_connection_failed, listOf(friendlyMessage))

    fun settingsRootUnavailableSyncBlocked(detailMessage: String): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_settings_root_unavailable_sync_blocked, listOf(detailMessage))

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

    fun homeBookDeleted(sourceFileKept: Boolean): FeedbackMessage =
        FeedbackMessage.Resource(
            if (sourceFileKept) {
                R.string.feedback_home_book_deleted_source_kept
            } else {
                R.string.feedback_home_book_deleted_source_missing
            }
        )

    fun homeReadStatusUpdated(readStatus: String): FeedbackMessage =
        FeedbackMessage.Resource(
            when (readStatus) {
                com.viel.aplayer.data.db.AudiobookSchema.ReadStatus.NOT_STARTED ->
                    R.string.feedback_home_read_status_not_started
                com.viel.aplayer.data.db.AudiobookSchema.ReadStatus.IN_PROGRESS ->
                    R.string.feedback_home_read_status_in_progress
                com.viel.aplayer.data.db.AudiobookSchema.ReadStatus.FINISHED ->
                    R.string.feedback_home_read_status_finished
                else -> R.string.feedback_home_read_status_updated
            }
        )

    fun homeCoverMetadataRegenerationStarted(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_home_cover_metadata_regeneration_started)

    fun homeCoverMetadataRegenerationCompleted(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_home_cover_metadata_regeneration_completed)

    fun absBackgroundSyncRootMissing(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_abs_background_sync_root_missing)

    fun absBackgroundSyncUnavailable(detailMessage: String): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_abs_background_sync_unavailable, listOf(detailMessage))

    fun absBackgroundSyncCompleted(addedBooks: Int, failedItems: Int): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_abs_background_sync_completed, listOf(addedBooks, failedItems))

    fun absBackgroundSyncFailed(redactedMessage: String): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_abs_background_sync_failed, listOf(redactedMessage))

    fun scanCompletedWithDiscoveredBooks(discoveredCount: Int): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_scan_completed_with_discovered_books, listOf(discoveredCount))

    fun scanCompleted(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_scan_completed)

    fun scanLibraryEmpty(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_scan_library_empty)

    fun scanAlreadyUpToDate(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_scan_already_up_to_date)

    fun scanCompletedSuffixUpdated(updatedCount: Int): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_scan_suffix_updated, listOf(updatedCount))

    fun scanCompletedSuffixPartial(partialCount: Int): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_scan_suffix_partial, listOf(partialCount))

    fun scanBlockedNoDirectoryRoots(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_scan_blocked_no_directory_roots)

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

    fun chapterPhysicalFileMissing(): FeedbackMessage =
        FeedbackMessage.Resource(R.string.feedback_chapter_physical_file_missing)
}

/**
 * Feedback Message Rendering (Resolves resource-backed feedback at the app-shell edge)
 *
 * Rendering is intentionally outside event producers so media, workers, and ViewModels emit facts rather
 * than localized presentation text.
 */
fun FeedbackMessage.render(context: Context): String =
    when (this) {
        is FeedbackMessage.RawText -> value
        is FeedbackMessage.Resource -> context.getString(resId, *args.toTypedArray())
        is FeedbackMessage.Composite -> parts.joinToString(separator = "") { part -> part.render(context) }
    }
