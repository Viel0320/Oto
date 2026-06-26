package com.viel.oto.application.library.settings

import com.viel.oto.data.db.AudiobookSchema

/**
 * Room-free representation model for settings root management.
 * Carries display metadata and computed projection states to decouple screens from DB schemas.
 */
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
    val isAbsRoot: Boolean
        get() = sourceType == AudiobookSchema.LibrarySourceType.ABS
    val isWebDavRoot: Boolean
        get() = sourceType == AudiobookSchema.LibrarySourceType.WEBDAV
    val isSafRoot: Boolean
        get() = sourceType == AudiobookSchema.LibrarySourceType.SAF
}

data class SettingsCredential(
    val username: String,
    val password: String
)
