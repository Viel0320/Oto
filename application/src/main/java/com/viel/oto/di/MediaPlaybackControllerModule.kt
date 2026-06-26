package com.viel.oto.di

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.oto.application.playback.DefaultPlayerPlaybackController
import com.viel.oto.application.playback.PlayerPlaybackController
import com.viel.oto.media.AutoRewindManager
import com.viel.oto.media.PlaybackManager
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Player-scene playback controller wiring.
 * Kept separate from MediaModule so the player controller can evolve without touching media runtime.
 */
@OptIn(UnstableApi::class)
object MediaPlaybackControllerModule {

    val module: Module = module {
        single<PlayerPlaybackController> {
            DefaultPlayerPlaybackController(
                playbackManager = get<PlaybackManager>(),
                autoRewindManager = get<AutoRewindManager>()
            )
        }
    }
}
