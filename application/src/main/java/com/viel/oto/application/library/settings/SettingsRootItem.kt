package com.viel.oto.application.library.settings

import com.viel.oto.application.usecase.SettingsRootSourceKind
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
    val sourceKind: SettingsRootSourceKind
        get() = when (sourceType) {
            AudiobookSchema.LibrarySourceType.SAF -> SettingsRootSourceKind.SAF
            AudiobookSchema.LibrarySourceType.WEBDAV -> SettingsRootSourceKind.WEB_DAV
            AudiobookSchema.LibrarySourceType.ABS -> SettingsRootSourceKind.ABS
        }

    val isAbsRoot: Boolean
        get() = sourceKind == SettingsRootSourceKind.ABS
    val isWebDavRoot: Boolean
        get() = sourceKind == SettingsRootSourceKind.WEB_DAV
    val isSafRoot: Boolean
        get() = sourceKind == SettingsRootSourceKind.SAF
}

data class SettingsCredential(
    val username: String,
    val password: String
)
