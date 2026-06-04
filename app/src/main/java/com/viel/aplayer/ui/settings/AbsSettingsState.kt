package com.viel.aplayer.ui.settings

data class AbsServerSettingsState(
    val rootId: String,
    val displayName: String,
    val baseUrl: String,
    val libraryId: String,
    val syncStatus: String,
    val lastFullSyncAt: Long? = null,
    val serverVersion: String? = null,
    val lastError: String? = null
)

data class AbsLibraryOptionState(
    val id: String,
    val name: String
)

data class AbsConnectionUiState(
    val isTesting: Boolean = false,
    val baseUrl: String = "",
    val username: String = "",
    val serverVersion: String? = null,
    val libraries: List<AbsLibraryOptionState> = emptyList(),
    val lastError: String? = null,
    val loginSucceeded: Boolean = false
)

data class AbsSyncConfirmationState(
    val rootId: String,
    val totalItems: Int
)

// WebDAV connection state model: Add UI state class to hold connection test status of WebDAV libraries.
data class WebDavConnectionUiState(
    val isTesting: Boolean = false,
    val testSucceeded: Boolean = false,
    val lastError: String? = null
)
