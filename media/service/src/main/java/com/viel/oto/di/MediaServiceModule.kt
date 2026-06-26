package com.viel.oto.di

import com.viel.oto.data.db.AppDatabase
import com.viel.oto.media.PlaybackSessionTokenFactory
import com.viel.oto.media.service.AndroidManualDownloadNotificationGateway
import com.viel.oto.media.service.DownloadNotificationResources
import com.viel.oto.media.service.MediaServiceLaunchIntentFactory
import com.viel.oto.media.service.PlaybackServiceSessionTokenFactory
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Media service owned adapters for Android service integration.
 *
 * App-owned bindings still provide resources and launch intents, while this module owns concrete
 * service implementations that name PlaybackService or assemble Android notifications.
 */
object MediaServiceModule {

    val module: Module = module {
        single<PlaybackSessionTokenFactory> { PlaybackServiceSessionTokenFactory() }

        single {
            AndroidManualDownloadNotificationGateway(
                context = get(),
                bookDao = get<AppDatabase>().bookDao(),
                launchIntentFactory = get<MediaServiceLaunchIntentFactory>(),
                notificationResources = get<DownloadNotificationResources>()
            )
        }
    }
}
