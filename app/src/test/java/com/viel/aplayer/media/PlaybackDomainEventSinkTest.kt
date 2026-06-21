package com.viel.aplayer.media

import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDomainEventSinkTest {
    @Test
    fun `playback stream reports dropped when no bridge collector is active`() {
        val sink = DefaultPlaybackDomainEventSink()
        val result = sink.emit(PlaybackDomainEvent.BookmarkCreated(bookId = "book-1", positionMs = 1_000L))

        assertTrue(result is PlaybackDomainEventDeliveryResult.Dropped)
    }
}
