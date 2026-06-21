package com.viel.aplayer.application.usecase

import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavConnectionTester

class TestWebDavConnectionUseCase(
    private val webDavConnectionTester: WebDavConnectionTester,
    private val settingsQueryUseCase: SettingsQueryUseCase
) {
    suspend fun execute(
        url: String,
        username: String,
        password: String,
        basePath: String,
        editingRootId: String? = null
    ) {
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
