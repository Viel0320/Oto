package com.viel.oto.ui.libraryManagement



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

data class WebDavConnectionUiState(
    val isTesting: Boolean = false,
    val testSucceeded: Boolean = false,
    val lastError: String? = null
)
