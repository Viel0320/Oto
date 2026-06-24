package com.viel.oto.di

import com.viel.oto.event.AppEventSink
import com.viel.oto.event.DefaultAppEventSink
import com.viel.oto.event.PlaybackDomainEventBridge
import com.viel.oto.media.DefaultPlaybackDomainEventSink
import com.viel.oto.media.PlaybackDomainEventSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.Closeable

/**
 * Process-wide feedback streams and the playback domain event bridge.
 * Replaces UiEventGraph with Koin-managed single definitions and registers the bridge scope for
 * ordered shutdown.
 */
internal object UiEventModule {

    val module: Module = module {
        single(UiEventScopeQualifier) {
            CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()).also { scope ->
                GraphClosePolicy.register(
                    stage = GraphClosePolicy.Stage.UiEvents,
                    priority = 10,
                    closeable = Closeable { scope.cancel() }
                )
            }
        }

        single<AppEventSink> { DefaultAppEventSink(scope = get(UiEventScopeQualifier)) }

        single<PlaybackDomainEventSink> { DefaultPlaybackDomainEventSink() }

        single(createdAtStart = true) {
            PlaybackDomainEventBridge(
                scope = get(UiEventScopeQualifier),
                playbackEvents = get<PlaybackDomainEventSink>().events,
                appEventSink = get()
            ).also { bridge ->
                GraphClosePolicy.register(
                    stage = GraphClosePolicy.Stage.UiEvents,
                    priority = 0,
                    closeable = Closeable { bridge.close() }
                )
            }
        }
    }
}
