package com.viel.aplayer.application.library.settings

import com.viel.aplayer.application.download.DownloadRuntimeGateway

/**
 * Bridge persisted download policy to an already-created runtime.
 *
 * AppSettingsRepository remains pure DataStore storage, while this decorator performs the optional
 * Media3 requirements update only when the manual download runtime is already alive.
 */
class DownloadAwareAppSettingsCommands(
    private val delegate: AppSettingsCommands,
    private val downloadRuntimeGatewayProvider: () -> DownloadRuntimeGateway,
    private val isDownloadRuntimeInitialized: () -> Boolean
) : AppSettingsCommands by delegate {
    override suspend fun updateDownloadWifiOnly(enabled: Boolean) {
        delegate.updateDownloadWifiOnly(enabled)
        if (isDownloadRuntimeInitialized()) {
            downloadRuntimeGatewayProvider().updateRequirements(enabled)
        }
    }
}
