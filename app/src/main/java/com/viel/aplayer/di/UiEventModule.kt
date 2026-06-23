package com.viel.aplayer.di

import com.viel.aplayer.event.AppEventSink
import com.viel.aplayer.event.DefaultAppEventSink
import com.viel.aplayer.event.PlaybackDomainEventBridge
import com.viel.aplayer.media.DefaultPlaybackDomainEventSink
import com.viel.aplayer.media.PlaybackDomainEventSink
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
