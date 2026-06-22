package com.viel.aplayer.ui.settings.remote

/**
 * Which remote-login form the standalone connection overlay is currently showing.
 * [None] keeps the overlay hidden; the app shell derives the overlay's visibility from this value.
 */
enum class RemoteConnectionSource { None, WebDav, Abs }

/**
 * Edit-buffer for the WebDAV/ABS connection forms, owned by [RemoteConnectionViewModel] so it
 * survives the overlay's AnimatedVisibility and configuration changes. [editingRootId] is non-null
 * when editing an existing remote root rather than adding a new one.
 */
data class RemoteConnectionFormState(
    val source: RemoteConnectionSource = RemoteConnectionSource.None,
    val editingRootId: String? = null,
    val webDavUrl: String = "",
    val webDavUsername: String = "",
    val webDavPassword: String = "",
    val webDavDisplayName: String = "",
    val webDavBasePath: String = "",
    val absBaseUrl: String = "",
    val absUsername: String = "",
    val absPassword: String = "",
    val absLibraryId: String = "",
    val absLibraryName: String = "",
    val absDisplayName: String = ""
)
