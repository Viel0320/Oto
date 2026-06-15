package com.viel.aplayer.application.library.settings

import com.viel.aplayer.application.download.DownloadRuntimeGateway

/**
 * Download-Aware Settings Commands (Bridge persisted download policy to an already-created runtime)
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
        // Download WiFi Runtime Update (Persist first, then update active Media3 requirements if the runtime exists)
        // Skipping the gateway when the runtime is cold preserves the smart-start promise for settings-only changes.
        delegate.updateDownloadWifiOnly(enabled)
        if (isDownloadRuntimeInitialized()) {
            downloadRuntimeGatewayProvider().updateRequirements(enabled)
        }
    }
}
