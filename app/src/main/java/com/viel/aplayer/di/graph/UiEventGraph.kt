package com.viel.aplayer.di.graph

import com.viel.aplayer.event.AppEventSink
import com.viel.aplayer.event.DefaultAppEventSink
import com.viel.aplayer.event.PlaybackDomainEventBridge
import com.viel.aplayer.media.DefaultPlaybackDomainEventSink
import com.viel.aplayer.media.PlaybackDomainEventSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.Closeable

/**
 * Owns process-wide feedback render streams and bridges.
 * Keeps app-shell feedback wiring separate from data, media, and remote catalog construction.
 */
internal class UiEventGraph : Closeable {
    private val eventBridgeScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    val appEventSink: AppEventSink by lazy {
        DefaultAppEventSink(scope = eventBridgeScope)
    }

    val playbackDomainEventSink: PlaybackDomainEventSink by lazy {
        DefaultPlaybackDomainEventSink()
    }

    private val playbackDomainEventBridgeLazy = lazy {
        PlaybackDomainEventBridge(
            scope = eventBridgeScope,
            playbackEvents = playbackDomainEventSink.events,
            appEventSink = appEventSink
        )
    }

    /**
     * Preserves eager bridge startup while retaining lazy lifecycle metadata.
     * The backing Lazy lets teardown close the bridge explicitly only after startEventBridges has initialized it.
     */
    private val playbackDomainEventBridge: PlaybackDomainEventBridge
        get() = playbackDomainEventBridgeLazy.value

    fun startEventBridges() {
        playbackDomainEventBridge
    }

    override fun close() {
        closeInitializedUiEventGraphResources(
            closeableResources = listOf(playbackDomainEventBridgeLazy),
            eventBridgeScope = eventBridgeScope
        )
    }
}
