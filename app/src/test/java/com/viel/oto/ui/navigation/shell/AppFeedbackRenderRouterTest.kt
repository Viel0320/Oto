package com.viel.oto.ui.navigation.shell

import com.viel.oto.event.AppShellEvent
import com.viel.oto.event.feedback.BookManagementFeedbackFacts
import com.viel.oto.event.feedback.FeedbackMessage
import com.viel.oto.event.feedback.FeedbackRenderMode
import com.viel.oto.event.feedback.RecoveryFeedbackFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AppFeedbackRenderRouterTest {
    private val renderRouter = DefaultAppFeedbackRenderRouter

    @Test
    fun `toast render mode maps to toast request without resolving Android resources`() {
        val fact = BookManagementFeedbackFacts.bookmarkCreated("book-1")

        val request = renderRouter.route(AppShellEvent.RenderFeedback(fact, FeedbackRenderMode.TOAST))

        assertSame(fact.message, (request as AppFeedbackRenderRequest.Toast).message)
    }

    @Test
    fun `dialog render mode maps to dialog request with playback identifiers`() {
        val fact = RecoveryFeedbackFacts.trackUnavailable(
            bookId = "book-42",
            queueIndex = 7,
            bookTitle = "Renderer Fixture"
        )

        val request = renderRouter.route(
            AppShellEvent.RenderFeedback(fact, FeedbackRenderMode.DIALOG)
        )

        val dialogRequest = request as AppFeedbackRenderRequest.Dialog
        val message = dialogRequest.message as FeedbackMessage.PlaybackTrackUnavailable
        assertSame(fact.message, dialogRequest.message)
        assertEquals("book-42", message.bookId)
        assertEquals(7, message.queueIndex)
        assertEquals("Renderer Fixture", message.bookTitle)
    }
}
