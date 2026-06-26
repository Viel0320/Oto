package com.viel.oto.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.oto.abs.vfs.AbsSourceProvider
import com.viel.oto.app.playback.AppMediaServiceLaunchIntentFactory
import com.viel.oto.app.playback.AppPlaybackResumePlanProvider
import com.viel.oto.app.presentation.AppPlaybackCommandPresentation
import com.viel.oto.app.widget.AppPlaybackWidgetStateSink
import com.viel.oto.library.vfs.sourceProvider.LibrarySourceProviderFactory
import com.viel.oto.media.service.MediaServiceLaunchIntentFactory
import com.viel.oto.media.service.PlaybackCommandPresentation
import com.viel.oto.media.service.PlaybackResumePlanProvider
import com.viel.oto.media.service.PlaybackWidgetStateSink
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Cross-module source composition and app-owned playback service adapters.
 */
@OptIn(UnstableApi::class)
internal object MediaModule {

    val module: Module = module {
        single {
            LibrarySourceProviderFactory(
                context = get<Context>().applicationContext,
                extraProviders = listOf(get<AbsSourceProvider>())
            )
        }

        single<MediaServiceLaunchIntentFactory> { AppMediaServiceLaunchIntentFactory() }

        single<PlaybackCommandPresentation> { AppPlaybackCommandPresentation() }

        single<PlaybackWidgetStateSink> { AppPlaybackWidgetStateSink() }

        single<PlaybackResumePlanProvider> { AppPlaybackResumePlanProvider(get()) }
    }
}
