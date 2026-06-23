package com.viel.aplayer.di

import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.application.download.DownloadRuntimeGateway
import com.viel.aplayer.application.library.settings.AppSettingsCommands
import com.viel.aplayer.application.library.settings.AppSettingsReadModel
import com.viel.aplayer.application.library.settings.DownloadAwareAppSettingsCommands
import com.viel.aplayer.data.AppSettingsRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Settings read model and command surface, including the download-aware command wrapper.
 * Replaces DataGraph's settingsReadModel / settingsCommands accessors.
 */
@UnstableApi
internal object CoreSettingsModule {

    val module: Module = module {
        single<AppSettingsReadModel> { get<AppSettingsRepository>() }
        single<AppSettingsCommands> {
            DownloadAwareAppSettingsCommands(
                delegate = get<AppSettingsRepository>(),
                downloadRuntimeGatewayProvider = { get<DownloadRuntimeGateway>() },
                isDownloadRuntimeInitialized = { get<DownloadRuntimeInitializedFlag>().value }
            )
        }
    }
}

/**
 * Lazy flag that mirrors DownloadGraph.isDownloadRuntimeInitialized.
 * Exposed as a Koin single so DownloadAwareAppSettingsCommands can observe download runtime state.
 */
internal class DownloadRuntimeInitializedFlag {
    @Volatile
    var value: Boolean = false
}
