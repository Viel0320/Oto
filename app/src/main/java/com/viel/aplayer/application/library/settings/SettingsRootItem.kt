package com.viel.aplayer.application.library.settings

import com.viel.aplayer.data.db.AudiobookSchema

/**
 * Settings Root Item (Room-free representation model for settings root management)
 * Carries display metadata and computed projection states to decouple screens from DB schemas.
 */
// Title: Relocate SettingsRootItem (Expose the representation model in the application layer to enforce single-directional architecture dependency)
// Shifting this data class to application package allows FormatSettingsRootUseCase to return it directly without cyclic UI package dependencies.
data class SettingsRootItem(
    val rootId: String,
    val sourceType: AudiobookSchema.LibrarySourceType,
    val sourceUri: String,
    val basePath: String,
    val credentialId: String?,
    val displayName: String,
    val title: String,
    val statusText: String,
    val locationText: String,
    val selectedLibraryText: String? = null,
    val lastSyncText: String,
    val importedBookCount: Int,
    val lastError: String? = null
) {
    // Title: SettingsRootItem calculated getters (Expose computed boolean properties to clear AudiobookSchema constants from UI compose code)
    // Providing isAbsRoot, isWebDavRoot and isSafRoot ensures the SettingsScreen UI can perform branching decisions without direct db-schema dependency.
    val isAbsRoot: Boolean
        get() = sourceType == AudiobookSchema.LibrarySourceType.ABS
    val isWebDavRoot: Boolean
        get() = sourceType == AudiobookSchema.LibrarySourceType.WEBDAV
    val isSafRoot: Boolean
        get() = sourceType == AudiobookSchema.LibrarySourceType.SAF
}

// Title: Add SettingsCredential model (Expose a database-free and VFS-free credentials model in application layer)
// Decouples Settings UI and ViewModel from importing com.viel.aplayer.abs.auth.AbsCredential and WebDavCredential.
data class SettingsCredential(
    val username: String,
    val password: String
)
