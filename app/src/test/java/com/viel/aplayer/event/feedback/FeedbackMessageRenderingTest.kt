package com.viel.aplayer.event.feedback

import com.viel.aplayer.R
import com.viel.aplayer.data.store.AppLanguage
import com.viel.aplayer.i18n.AppLocaleController
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Feedback Message Rendering Test (Exercises Android resource resolution for transient feedback)
 * Robolectric keeps the test on the real Resources path so plural selection is verified where Toast copy is rendered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class FeedbackMessageRenderingTest {

    @Test
    fun `quantity feedback renders singular and plural scan completion copy`() {
        val context = RuntimeEnvironment.getApplication()

        // Counted Scan Completion Rendering (Locks the user-visible singular and plural English forms)
        // This catches regressions where counted feedback accidentally routes through getString and renders "1 books".
        assertEquals(
            "Media library sync completed. Added 1 book.",
            FeedbackMessages.scanCompletedWithDiscoveredBooks(1).render(context)
        )
        assertEquals(
            "Media library sync completed. Added 2 books.",
            FeedbackMessages.scanCompletedWithDiscoveredBooks(2).render(context)
        )
    }

    @Test
    fun `composite quantity feedback renders both independent book counts`() {
        val context = RuntimeEnvironment.getApplication()

        // Multi-Count Feedback Rendering (Verifies each ABS sync count chooses its own plural branch)
        // Added and failed totals can differ, so the renderer must combine two quantity messages instead of formatting one plain string.
        assertEquals(
            "ABS background sync completed: added 1 book, failed 2 books",
            FeedbackMessages.absBackgroundSyncCompleted(1, 2).render(context)
        )
        assertEquals(
            "ABS background sync completed: added 2 books, failed 1 book",
            FeedbackMessages.absBackgroundSyncCompleted(2, 1).render(context)
        )
    }

    @Test
    fun `manual quantity feedback uses Android plural resources directly`() {
        val context = RuntimeEnvironment.getApplication()

        // Generic Quantity Rendering (Covers the FeedbackMessage.Quantity branch independently from factory helpers)
        // A direct quantity message proves render delegates to getQuantityString with the stored resource, quantity, and args.
        val one = FeedbackMessage.Quantity(R.plurals.feedback_sleep_timer_minutes, quantity = 1)
        val many = FeedbackMessage.Quantity(R.plurals.feedback_sleep_timer_minutes, quantity = 2)

        assertEquals("Sleep timer: 1 minute", one.render(context))
        assertEquals("Sleep timer: 2 minutes", many.render(context))
    }

    @Test
    fun `feedback render follows localized context resources`() {
        val context = RuntimeEnvironment.getApplication()
        val localizedContext = AppLocaleController.wrapContext(context, AppLanguage.Japanese)

        // Localized Feedback Resource Rendering (Verifies the renderer consumes the supplied configuration context)
        // Android 12L routes in-app language through wrapContext, so Toast feedback must resolve copy from that context instead of the Activity base resources.
        assertEquals(
            "\u518D\u751F\u901F\u5EA6\u3092\u30EA\u30BB\u30C3\u30C8\u3057\u307E\u3057\u305F",
            FeedbackMessages.playbackSpeedReset().render(localizedContext)
        )
    }
}
