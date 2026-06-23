package com.viel.aplayer.di.koin

import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.application.playback.PlaybackStopper
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.library.vfs.cache.RoomDirectoryListingCache
import com.viel.aplayer.library.vfs.cache.VfsRangeCache
import com.viel.aplayer.media.AutoRewindManager
import com.viel.aplayer.media.DefaultPlaybackFileLookup
import com.viel.aplayer.media.DefaultPlaybackRootLookup
import com.viel.aplayer.media.PlaybackFileLookup
import com.viel.aplayer.media.PlaybackManager
import com.viel.aplayer.media.PlaybackRootLookup
import com.viel.aplayer.media.PlaybackSourcePreflight
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.Closeable

/**
 * VFS, playback runtime, and playback-file infrastructure.
 * Replaces MediaGraph with Koin-managed single definitions and registers the playback runtime for
 * ordered shutdown.
 */
@UnstableApi
internal object MediaModule {

    private class PlaybackRuntimeHolder(
        private val playbackManager: PlaybackManager
    ) : Closeable {
        override fun close() {
            runCatching { playbackManager.release() }
        }
    }

    val module: Module = module {
        single { VfsRangeCache(get<android.content.Context>()) }

        single { RoomDirectoryListingCache(directoryChildCacheDao = get<AppDatabase>().directoryChildCacheDao()) }

        single<com.viel.aplayer.library.vfs.cache.DirectoryListingCache> { get<RoomDirectoryListingCache>() }

        single { AutoRewindManager(get(), get(), get()) }

        single {
            PlaybackManager(get(), get(), get(), get()).also { playbackManager ->
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
                rangeCache = get()
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
