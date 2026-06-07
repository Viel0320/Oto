package com.viel.aplayer.media

import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDomainEventSinkTest {
    @Test
    fun `playback stream reports dropped when no bridge collector is active`() {
        // Missing Bridge Scenario (Makes playback event loss visible when the app-layer bridge is not attached)
        // This protects the media sink interface from exposing a bare tryEmit Boolean with ambiguous semantics.
        val sink = DefaultPlaybackDomainEventSink()
        val result = sink.emit(PlaybackDomainEvent.BookmarkCreated(bookId = "book-1", positionMs = 1_000L))

        assertTrue(result is PlaybackDomainEventDeliveryResult.Dropped)
    }
}
