package com.viel.oto.di

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.oto.application.download.DownloadRuntimeInitializedFlag
import com.viel.oto.application.download.DownloadRuntimeGateway
import com.viel.oto.application.library.settings.AppSettingsCommands
import com.viel.oto.application.library.settings.AppSettingsReadModel
import com.viel.oto.application.library.settings.DownloadAwareAppSettingsCommands
import com.viel.oto.application.library.settings.RepositoryAppSettingsAdapter
import com.viel.oto.data.AppSettingsRepository
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Settings command surface, including the download-aware command wrapper.
 *
 * AppSettingsRepository stays data-owned; this module adapts it to application read and command
 * contracts, then wraps commands that need to notify an already-created download runtime.
 */
@OptIn(UnstableApi::class)
object CoreSettingsModule {

    val module: Module = module {
        single { RepositoryAppSettingsAdapter(get<AppSettingsRepository>()) } bind AppSettingsReadModel::class
        single<AppSettingsCommands> {
            DownloadAwareAppSettingsCommands(
                delegate = get<RepositoryAppSettingsAdapter>(),
                downloadRuntimeGatewayProvider = { get<DownloadRuntimeGateway>() },
                isDownloadRuntimeInitialized = { get<DownloadRuntimeInitializedFlag>().value }
            )
        }
    }
}
