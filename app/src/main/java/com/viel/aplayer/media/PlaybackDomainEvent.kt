package com.viel.aplayer.media

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Playback Domain Event (Media-core facts that may require application feedback)
 *
 * These events intentionally avoid AppShellEvent, Toast, and Compose types so playback modules can report
 * domain outcomes without knowing how the foreground app renders them.
 */
sealed interface PlaybackDomainEvent {
    /**
     * Source Preflight Blocked (Playback plan cannot be applied because its source root is inactive)
     *
     * Carries the already domain-formatted availability message produced before media source creation.
     */
    data class SourcePreflightBlocked(val message: String) : PlaybackDomainEvent

    /**
     * Cleartext Playback Blocked (HTTP playback was rejected by the user's security preference)
     *
     * Represents the security decision as a playback fact rather than a direct UI command.
     */
    data object CleartextPlaybackBlocked : PlaybackDomainEvent

    /**
     * Track Unavailable (Current queue item failed reachability checks during runtime playback)
     *
     * The app shell can pair this event with both a toast and the existing skip-confirmation dialog.
     */
    data class TrackUnavailable(val bookId: String, val queueIndex: Int) : PlaybackDomainEvent

    /**
     * Initial Media Load Failed (The selected media item failed before producing playback)
     *
     * Initial failures are reported without marking the file missing because runtime recovery has not started.
     */
    data class InitialMediaLoadFailed(val errorMessage: String) : PlaybackDomainEvent

    /**
     * No Available Track After Failure (Self-healing could not find a later playable queue item)
     *
     * Keeps the exhausted failover result in the playback domain while presentation owns the notification.
     */
    data object NoAvailableTrackAfterFailure : PlaybackDomainEvent

    /**
     * Playback Finished Shutdown Scheduled (The service will close after the completed queue grace period)
     *
     * Exposes the grace period as data so the app-shell wording stays outside media service logic.
     */
    data class PlaybackFinishedShutdownScheduled(val delaySeconds: Int) : PlaybackDomainEvent

    /**
     * Bookmark Created (A media-session bookmark command persisted the current playback position)
     *
     * The event carries the domain coordinates even though the current UI feedback only needs a short toast.
     */
    data class BookmarkCreated(val bookId: String, val positionMs: Long) : PlaybackDomainEvent
}

/**
 * Playback Domain Event Sink (Hot stream used by media modules to publish playback facts)
 *
 * Media callers depend on this narrow sink instead of AppEventSink, preserving the rule that playback-core
 * code emits domain events and never constructs app-shell UI events directly.
 */
interface PlaybackDomainEventSink {
    val events: SharedFlow<PlaybackDomainEvent>

    /**
     * Emit Playback Event (Publishes a domain event without blocking the media thread)
     *
     * The structured result reports whether the media fact entered the stream, keeping tryEmit behavior
     * observable without making callers know about SharedFlow internals.
     */
    fun emit(event: PlaybackDomainEvent): PlaybackDomainEventDeliveryResult
}

/**
 * Playback Domain Event Delivery Result (Observable publication result for media facts)
 *
 * Media callers still fire-and-forget at runtime, while tests can assert whether a playback fact was
 * accepted or dropped by the hot stream.
 */
sealed interface PlaybackDomainEventDeliveryResult {
    val event: PlaybackDomainEvent
    val delivered: Boolean

    /**
     * Delivered Playback Event (The fact entered the playback-domain event stream)
     *
     * The bridge may render it later; this result only confirms stream acceptance.
     */
    data class Delivered(
        override val event: PlaybackDomainEvent
    ) : PlaybackDomainEventDeliveryResult {
        override val delivered: Boolean = true
    }

    /**
     * Dropped Playback Event (The stream rejected the playback fact)
     *
     * This makes buffer pressure visible to tests and diagnostics instead of hiding it behind a bare
     * Boolean return value.
     */
    data class Dropped(
        override val event: PlaybackDomainEvent
    ) : PlaybackDomainEventDeliveryResult {
        override val delivered: Boolean = false
    }
}

/**
 * Default Playback Domain Event Sink (Buffered event stream for playback recovery bursts)
 *
 * Runtime errors can emit a toast and dialog-worthy fact close together, so the buffer absorbs short
 * bursts while the application bridge collector is active and reports drops when no bridge is attached.
 */
class DefaultPlaybackDomainEventSink : PlaybackDomainEventSink {
    private val _events = MutableSharedFlow<PlaybackDomainEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    override val events: SharedFlow<PlaybackDomainEvent> = _events.asSharedFlow()

    override fun emit(event: PlaybackDomainEvent): PlaybackDomainEventDeliveryResult =
        if (_events.subscriptionCount.value > 0 && _events.tryEmit(event)) {
            PlaybackDomainEventDeliveryResult.Delivered(event)
        } else {
            PlaybackDomainEventDeliveryResult.Dropped(event)
        }
}
