package com.viel.oto.di

import com.viel.oto.app.download.AppDownloadNotificationResources
import com.viel.oto.media.service.DownloadNotificationResources
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * App-owned download notification resource binding.
 *
 * Download runtime and service commands are owned by the media service module, while the app shell
 * keeps launcher-icon identity and shared localized resource selection behind this narrow adapter.
 */
internal object AppDownloadResourceModule {

    val module: Module = module {
        single<DownloadNotificationResources> { AppDownloadNotificationResources() }
    }
}
