package com.viel.aplayer.event

import com.viel.aplayer.event.feedback.BookManagementFeedbackFacts
import com.viel.aplayer.event.feedback.FeedbackFact
import com.viel.aplayer.event.feedback.PlaybackControlFeedbackFacts
import com.viel.aplayer.event.feedback.RecoveryFeedbackFacts
import com.viel.aplayer.media.PlaybackDomainEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Translates media-core facts into app-shell feedback.
 *
 * Media modules emit playback-domain events only; this adapter is the application-layer seam that decides
 * which render mode each fact uses for the current UI shell.
 */
class PlaybackDomainEventBridge(
    scope: CoroutineScope,
    playbackEvents: SharedFlow<PlaybackDomainEvent>,
    private val appEventSink: AppEventSink
) : java.io.Closeable {
    private val job: Job = scope.launch {
        playbackEvents.collect { event ->
            when (event) {
                is PlaybackDomainEvent.CleartextPlaybackBlocked,
                is PlaybackDomainEvent.InitialMediaLoadFailed,
                is PlaybackDomainEvent.NoAvailableTrackAfterFailure,
                is PlaybackDomainEvent.PlaybackFinishedShutdownScheduled,
                is PlaybackDomainEvent.BookmarkCreated,
                is PlaybackDomainEvent.SourcePreflightBlocked ->
                    appEventSink.emitFeedback(event.toFeedbackFact())

                is PlaybackDomainEvent.TrackUnavailable ->
                    appEventSink.emitFeedback(event.toFeedbackFact())
            }
        }
    }

    /**
     * Stops the collection job when the container is explicitly closed.
     *
     * The app process normally owns this bridge for its full lifetime, but tests and manual container teardown
     * need deterministic cancellation to avoid leaking coroutine collectors.
     */
    override fun close() {
        job.cancel()
    }
}

/**
 * Converts media facts into typed feedback facts.
 *
 * Keeping this mapping beside the bridge concentrates playback feedback classification outside media-core
 * callers. Each fact carries both the resource-backed message and the aggregation outcome the delivery
 * policy reasons about; final localized text rendering still happens in the app shell.
 */
internal fun PlaybackDomainEvent.toFeedbackFact(): FeedbackFact =
    when (this) {
        is PlaybackDomainEvent.CleartextPlaybackBlocked ->
            RecoveryFeedbackFacts.cleartextPlaybackBlocked(bookTitle)
        is PlaybackDomainEvent.InitialMediaLoadFailed ->
            RecoveryFeedbackFacts.initialMediaLoadFailed(errorMessage, bookTitle)
        is PlaybackDomainEvent.NoAvailableTrackAfterFailure ->
            RecoveryFeedbackFacts.noAvailableTrackAfterFailure(bookTitle)
        is PlaybackDomainEvent.PlaybackFinishedShutdownScheduled ->
            PlaybackControlFeedbackFacts.playbackFinishedShutdownScheduled(delaySeconds)
        is PlaybackDomainEvent.BookmarkCreated ->
            BookManagementFeedbackFacts.bookmarkCreated(bookId)
        is PlaybackDomainEvent.SourcePreflightBlocked ->
            RecoveryFeedbackFacts.sourcePreflightBlocked(reason, rootName, bookTitle)
        is PlaybackDomainEvent.TrackUnavailable ->
            RecoveryFeedbackFacts.trackUnavailable(bookId, queueIndex, bookTitle)
    }
