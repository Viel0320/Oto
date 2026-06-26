package com.viel.oto.di

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.oto.data.db.AppDatabase
import com.viel.oto.media.AutoRewindManager
import com.viel.oto.media.DefaultPlaybackFileLookup
import com.viel.oto.media.DefaultPlaybackRootLookup
import com.viel.oto.media.PlaybackFileLookup
import com.viel.oto.media.PlaybackManager
import com.viel.oto.media.PlaybackRootLookup
import com.viel.oto.media.PlaybackSourcePreflight
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.Closeable

/**
 * Playback runtime and Room-backed playback lookup bindings.
 *
 * Service and app adapters provide session tokens and presentation contracts from outside playback;
 * this module owns the playback engine lifecycle and source preflight definitions.
 */
@OptIn(UnstableApi::class)
object MediaPlaybackRuntimeModule {

    private class PlaybackRuntimeHolder(
        private val playbackManager: PlaybackManager
    ) : Closeable {
        override fun close() {
            runCatching { playbackManager.release() }
        }
    }

    val module: Module = module {
        single { AutoRewindManager(get(), get(), get(), get()) }

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
    }
}
