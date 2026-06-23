package com.viel.aplayer.di

import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.application.playback.DefaultPlayerPlaybackController
import com.viel.aplayer.application.playback.PlayerPlaybackController
import com.viel.aplayer.media.AutoRewindManager
import com.viel.aplayer.media.PlaybackManager
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Player-scene playback controller wiring.
 * Kept separate from MediaModule so the player controller can evolve without touching media runtime.
 */
@UnstableApi
internal object MediaPlaybackControllerModule {

    val module: Module = module {
        single<PlayerPlaybackController> {
            DefaultPlayerPlaybackController(
                playbackManager = get<PlaybackManager>(),
                autoRewindManager = get<AutoRewindManager>()
            )
        }
    }
}
