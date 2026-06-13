package com.viel.aplayer.ui.settings

// Title: Remove SettingsRootItem from AbsSettingsState (Remove presentation model definition after relocation to application layer)
// This file now only holds UI-scoped state definitions such as AbsServerSettingsState and WebDavConnectionUiState.


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
