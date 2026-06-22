package com.viel.aplayer.application.usecase

import com.viel.aplayer.data.root.LibraryRootGateway
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavConnectionTester

class TestWebDavConnectionUseCase(
    private val webDavConnectionTester: WebDavConnectionTester,
    private val settingsQueryUseCase: SettingsQueryUseCase,
    private val libraryRootGateway: LibraryRootGateway
) {
    /**
     * Tests a WebDAV draft after blocking duplicate base URLs for new-root forms.
     *
     * The duplicate guard runs before credential resolution and network I/O, while edit forms keep the
     * existing credential fallback behavior by passing their root id through [editingRootId].
     */
    suspend fun execute(
        url: String,
        username: String,
        password: String,
        basePath: String,
        editingRootId: String? = null
    ) {
        if (editingRootId.isNullOrBlank()) {
            requireUniqueWebDavRootForNewConnection(
                roots = libraryRootGateway.getAllRootsOnce(),
                url = url,
                basePath = basePath,
                editingRootId = editingRootId
            )
        }
        val resolvedCredentials = settingsQueryUseCase.resolveWebDavCredentials(
            username = username,
            password = password,
            editingRootId = editingRootId
        )
        webDavConnectionTester.testConnection(
            url = url,
            username = resolvedCredentials.username,
            password = resolvedCredentials.password,
            basePath = basePath
        )
    }
}
