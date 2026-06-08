package com.viel.aplayer.event

import com.viel.aplayer.event.feedback.FeedbackMessage
import com.viel.aplayer.event.feedback.FeedbackMessages
import com.viel.aplayer.media.PlaybackDomainEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Playback Domain Event Bridge (Translates media-core facts into app-shell feedback)
 *
 * Media modules emit playback-domain events only; this adapter is the application-layer seam that decides
 * which facts become Toasts or dialogs for the current UI shell.
 */
class PlaybackDomainEventBridge(
    scope: CoroutineScope,
    playbackEvents: SharedFlow<PlaybackDomainEvent>,
    private val appEventSink: AppEventSink
) : java.io.Closeable {
    private val job: Job = scope.launch {
        playbackEvents.collect { event ->
            when (event) {
                PlaybackDomainEvent.CleartextPlaybackBlocked,
                is PlaybackDomainEvent.InitialMediaLoadFailed,
                PlaybackDomainEvent.NoAvailableTrackAfterFailure,
                is PlaybackDomainEvent.PlaybackFinishedShutdownScheduled,
                is PlaybackDomainEvent.BookmarkCreated,
                is PlaybackDomainEvent.SourcePreflightBlocked ->
                    appEventSink.showToast(event.toFeedbackMessage())

                is PlaybackDomainEvent.TrackUnavailable -> {
                    appEventSink.showToast(event.toFeedbackMessage())
                    appEventSink.showTrackUnavailableDialog(event.bookId, event.queueIndex)
                }
            }
        }
    }

    /**
     * Bridge Shutdown (Stops the collection job when the container is explicitly closed)
     *
     * The app process normally owns this bridge for its full lifetime, but tests and manual container teardown
     * need deterministic cancellation to avoid leaking coroutine collectors.
     */
    override fun close() {
        job.cancel()
    }
}

/**
 * Playback Feedback Mapping (Converts media facts into resource-backed feedback keys)
 *
 * Keeping this mapping beside the bridge concentrates playback wording policy outside media-core callers
 * and leaves final localized text rendering to the app shell.
 */
internal fun PlaybackDomainEvent.toFeedbackMessage(): FeedbackMessage =
    when (this) {
        PlaybackDomainEvent.CleartextPlaybackBlocked ->
            FeedbackMessages.playbackCleartextBlocked()
        is PlaybackDomainEvent.InitialMediaLoadFailed ->
            FeedbackMessages.playbackInitialMediaLoadFailed(errorMessage)
        PlaybackDomainEvent.NoAvailableTrackAfterFailure ->
            FeedbackMessages.playbackNoAvailableTrackAfterFailure()
        is PlaybackDomainEvent.PlaybackFinishedShutdownScheduled ->
            FeedbackMessages.playbackFinishedShutdownScheduled(delaySeconds)
        is PlaybackDomainEvent.BookmarkCreated ->
            FeedbackMessages.playbackBookmarkCreated()
        is PlaybackDomainEvent.SourcePreflightBlocked ->
            FeedbackMessages.playbackSourcePreflightBlocked(reason, rootName)
        is PlaybackDomainEvent.TrackUnavailable ->
            FeedbackMessages.playbackTrackUnavailable()
    }
