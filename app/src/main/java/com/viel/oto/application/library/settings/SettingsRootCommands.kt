package com.viel.oto.application.library.settings

import android.net.Uri
import com.viel.oto.data.db.AudiobookSchema

/**
 * Entity-free preflight result for settings actions.
 * Gives SettingsViewModel only the decision data needed to ask for confirmation or render a
 * blocked-sync message, while root lookup and ABS plan inspection stay in the settings-root module.
 */
sealed interface SettingsAbsSyncInspection {
    data object MissingRoot : SettingsAbsSyncInspection
    data class Blocked(val reason: SettingsAbsSyncBlockedReason) : SettingsAbsSyncInspection
    data class Ready(
        val rootId: String,
        val displayName: String,
        val totalItems: Int,
        val requiresConfirmation: Boolean
    ) : SettingsAbsSyncInspection
}

/**
 * Source-neutral reason returned when a settings-triggered ABS sync cannot start.
 *
 * Application owns the preflight decision, while UI/event adapters own localized feedback rendering.
 */
data class SettingsAbsSyncBlockedReason(
    val rootId: String,
    val rootName: String,
    val availabilityStatus: AudiobookSchema.AvailabilityStatus,
    val fallbackCode: String
)

/**
 * Scene-level root management surface.
 * Groups settings-page root registration, reachability refresh, and manual scan triggers behind a small interface.
 */
interface SettingsRootCommands {
    /**
     * Register a SAF root from the settings page.
     * Hides the trigger label and root gateway from SettingsViewModel while preserving immediate user-initiated ingestion.
     */
    fun addLocalRootAndScheduleSync(uri: Uri)

    /**
     * Register a remote root from the settings page.
     * Keeps WebDAV persistence and the user sync trigger inside the settings-root module instead of the presentation layer.
     */
    fun addWebDavRootAndScheduleSync(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String
    )

    /**
     * Recheck registered root availability.
     * Runs overlay-triggered reachability updates without letting SettingsViewModel depend on root gateway methods.
     */
    suspend fun refreshAllRootStatuses()

    /**
     * Run root reachability and remote item-count preflight.
     * Keeps LibraryRootEntity, availability updates, and ABS plan objects inside the application module before the UI decides whether to request confirmation.
     */
    suspend fun inspectManualAbsSync(rootId: String): SettingsAbsSyncInspection

    /**
     * Queue a user-triggered AudiobookShelf catalog sync.
     * Lets SettingsViewModel schedule work by rootId without importing ABS task origin constants or worker details.
     */
    fun startManualAbsSync(rootId: String): Boolean

    /**
     * Queue first sync after adding or editing an AudiobookShelf root.
     * Preserves the separate AUTO_ADD origin without exposing task-coordinator types to presentation code.
     */
    fun startAutoAbsSync(rootId: String): Boolean

    /**
     * Queue a manual library scan.
     * Centralizes the USER trigger string while keeping the scan scoped to the root selected in the settings list.
     */
    fun scheduleUserSync(rootId: String)
}
