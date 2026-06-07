package com.viel.aplayer.ui.navigation.shell

import com.viel.aplayer.event.AppShellEvent
import com.viel.aplayer.event.feedback.FeedbackMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AppFeedbackRendererTest {
    private val renderer = DefaultAppFeedbackRenderer

    @Test
    fun `toast event maps to toast command without resolving Android resources`() {
        val message = FeedbackMessages.playbackBookmarkCreated()

        val command = renderer.render(AppShellEvent.ShowToast(message))

        assertSame(message, (command as AppFeedbackCommand.ToastMessage).message)
    }

    @Test
    fun `track unavailable event maps to dialog command with playback identifiers`() {
        val command = renderer.render(
            AppShellEvent.ShowTrackUnavailableDialog(
                bookId = "book-42",
                queueIndex = 7
            )
        )

        val dialogCommand = command as AppFeedbackCommand.TrackUnavailableDialog
        assertEquals("book-42", dialogCommand.bookId)
        assertEquals(7, dialogCommand.queueIndex)
    }
}
