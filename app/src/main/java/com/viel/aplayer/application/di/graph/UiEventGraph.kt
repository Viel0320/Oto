package com.viel.aplayer.application.di.graph

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
 * UI Event Graph (Owns process-wide transient feedback streams and bridges)
 * Keeps app-shell feedback wiring separate from data, media, and remote catalog construction.
 */
internal class UiEventGraph : Closeable {
    private val eventBridgeScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    val appEventSink: AppEventSink by lazy {
        // Application Event Sink Initialization (Centralizes app-shell feedback dispatch)
        // Replaces PlaybackManager as the accidental shared event bus for ViewModels, workers, and services.
        DefaultAppEventSink()
    }

    val playbackDomainEventSink: PlaybackDomainEventSink by lazy {
        // Playback Domain Event Sink Initialization (Restricts media feedback to playback facts)
        // Media-core modules publish domain events here instead of importing UI event models.
        DefaultPlaybackDomainEventSink()
    }

    private val playbackDomainEventBridgeLazy = lazy {
        // Playback Event Bridge Initialization (Application-layer translation between media facts and UI events)
        // Keeps rendering policy in the app layer while preserving a narrow media-domain event stream.
        PlaybackDomainEventBridge(
            scope = eventBridgeScope,
            playbackEvents = playbackDomainEventSink.events,
            appEventSink = appEventSink
        )
    }

    /**
     * Playback Domain Event Bridge Accessor (Preserves eager bridge startup while retaining lazy lifecycle metadata)
     * The backing Lazy lets teardown close the bridge explicitly only after startEventBridges has initialized it.
     */
    private val playbackDomainEventBridge: PlaybackDomainEventBridge
        get() = playbackDomainEventBridgeLazy.value

    fun startEventBridges() {
        // Event Bridge Startup (Eagerly attach playback event translation during container creation)
        // Starting the bridge before playback services emit events prevents lifecycle timing from dropping early feedback.
        playbackDomainEventBridge
    }

    override fun close() {
        // Initialized Event Bridge Disposal (Close bridge collectors before cancelling the owning scope)
        // This gives each app-shell translator a deterministic close hook while retaining scope cancellation as the final leak barrier.
        closeInitializedUiEventGraphResources(
            closeableResources = listOf(playbackDomainEventBridgeLazy),
            eventBridgeScope = eventBridgeScope
        )
    }
}
