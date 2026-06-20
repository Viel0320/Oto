package com.viel.aplayer.application.library.settings

import android.net.Uri
import com.viel.aplayer.event.feedback.FeedbackFact

/**
 * Settings ABS Sync Inspection Result (Entity-free preflight result for settings actions)
 * Gives SettingsViewModel only the decision data needed to ask for confirmation or show a blocked-sync message, while root lookup and ABS plan inspection stay in the settings-root module.
 */
sealed interface SettingsAbsSyncInspection {
    data object MissingRoot : SettingsAbsSyncInspection
    data class Blocked(val fact: FeedbackFact) : SettingsAbsSyncInspection
    data class Ready(
        val rootId: String,
        val displayName: String,
        val totalItems: Int,
        val requiresConfirmation: Boolean
    ) : SettingsAbsSyncInspection
}

/**
 * Settings Root Commands (Scene-level root management surface)
 * Groups settings-page root registration, reachability refresh, and manual scan triggers behind a small interface.
 */
interface SettingsRootCommands {
    /**
     * Add Local Root and Schedule Sync (Register a SAF root from the settings page)
     * Hides the trigger label and root gateway from SettingsViewModel while preserving immediate user-initiated ingestion.
     */
    fun addLocalRootAndScheduleSync(uri: Uri)

    /**
     * Add WebDAV Root and Schedule Sync (Register a remote root from the settings page)
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
     * Refresh All Root Statuses (Recheck registered root availability)
     * Runs overlay-triggered reachability updates without letting SettingsViewModel depend on root gateway methods.
     */
    suspend fun refreshAllRootStatuses()

    /**
     * Inspect Manual ABS Sync (Run root reachability and remote item-count preflight)
     * Keeps LibraryRootEntity, availability updates, and ABS plan objects inside the application module before the UI decides whether to request confirmation.
     */
    suspend fun inspectManualAbsSync(rootId: String): SettingsAbsSyncInspection

    /**
     * Start Manual ABS Sync (Queue a user-triggered Audiobookshelf catalog sync)
     * Lets SettingsViewModel schedule work by rootId without importing ABS task origin constants or worker details.
     */
    fun startManualAbsSync(rootId: String): Boolean

    /**
     * Start Automatic ABS Sync (Queue first sync after adding or editing an Audiobookshelf root)
     * Preserves the separate AUTO_ADD origin without exposing task-coordinator types to presentation code.
     */
    fun startAutoAbsSync(rootId: String): Boolean

    /**
     * Schedule User Sync (Queue a manual library scan)
     * Centralizes the USER trigger string so settings UI actions do not know scheduler protocol details.
     */
    fun scheduleUserSync()
}
