package com.viel.oto.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.oto.abs.vfs.AbsSourceProvider
import com.viel.oto.app.playback.AppMediaServiceLaunchIntentFactory
import com.viel.oto.app.playback.AppPlaybackResumePlanProvider
import com.viel.oto.app.presentation.AppPlaybackCommandPresentation
import com.viel.oto.app.widget.AppPlaybackWidgetStateSink
import com.viel.oto.application.playback.PlaybackStopper
import com.viel.oto.data.db.AppDatabase
import com.viel.oto.library.vfs.VfsFileInterface
import com.viel.oto.library.vfs.cache.DirectoryListingCache
import com.viel.oto.library.vfs.cache.RoomDirectoryListingCache
import com.viel.oto.library.vfs.cache.VfsRangeCache
import com.viel.oto.library.vfs.sourceProvider.LibrarySourceProviderFactory
import com.viel.oto.media.AutoRewindManager
import com.viel.oto.media.DefaultPlaybackFileLookup
import com.viel.oto.media.DefaultPlaybackRootLookup
import com.viel.oto.media.PlaybackFileLookup
import com.viel.oto.media.PlaybackManager
import com.viel.oto.media.PlaybackRootLookup
import com.viel.oto.media.PlaybackSourcePreflight
import com.viel.oto.media.service.MediaServiceLaunchIntentFactory
import com.viel.oto.media.service.PlaybackCommandPresentation
import com.viel.oto.media.service.PlaybackResumePlanProvider
import com.viel.oto.media.service.PlaybackWidgetStateSink
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import java.io.Closeable

/**
 * VFS, playback runtime, and playback-file infrastructure.
 * Replaces MediaGraph with Koin-managed single definitions and registers the playback runtime for
 * ordered shutdown.
 *
 * DirectoryListingCache is bound from the Room implementation definition to avoid a second
 * provider whose only job is resolving the concrete cache back out of Koin.
 */
@OptIn(UnstableApi::class)
internal object MediaModule {

    private class PlaybackRuntimeHolder(
        private val playbackManager: PlaybackManager
    ) : Closeable {
        override fun close() {
            runCatching { playbackManager.release() }
        }
    }

    val module: Module = module {
        single {
            LibrarySourceProviderFactory(
                context = get<Context>().applicationContext,
                extraProviders = listOf(get<AbsSourceProvider>())
            )
        }

        single { VfsRangeCache(get<Context>()) }

        single { RoomDirectoryListingCache(directoryChildCacheDao = get<AppDatabase>().directoryChildCacheDao()) } bind DirectoryListingCache::class

        single { AutoRewindManager(get(), get(), get(), get()) }

        single<MediaServiceLaunchIntentFactory> { AppMediaServiceLaunchIntentFactory() }

        single<PlaybackCommandPresentation> { AppPlaybackCommandPresentation() }

        single<PlaybackWidgetStateSink> { AppPlaybackWidgetStateSink() }

        single<PlaybackResumePlanProvider> { AppPlaybackResumePlanProvider(get()) }

        single {
            PlaybackManager(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()).also { playbackManager ->
                GraphClosePolicy.register(
                    stage = GraphClosePolicy.Stage.Media,
                    closeable = PlaybackRuntimeHolder(playbackManager)
                )
            }
        }

        single<PlaybackFileLookup> { DefaultPlaybackFileLookup(get<AppDatabase>().bookDao()) }

        single<PlaybackRootLookup> { DefaultPlaybackRootLookup(get<AppDatabase>().libraryRootDao()) }

        single { PlaybackSourcePreflight(get<AppDatabase>().libraryRootDao()) }

        single {
            VfsFileInterface(
                get(),
                libraryRootDao = get<AppDatabase>().libraryRootDao(),
                rangeCache = get(),
                providerFactory = get<LibrarySourceProviderFactory>()
            )
        }

        single<PlaybackStopper> {
            object : PlaybackStopper {
                override val currentPlayingBookId: String?
                    get() = get<PlaybackManager>().currentPlayingBookId

                override suspend fun stopPlayback() {
                    get<PlaybackManager>().stopPlayback()
                }
            }
        }
    }
}
