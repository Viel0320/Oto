package com.viel.aplayer.event.feedback

import com.viel.aplayer.R
import com.viel.aplayer.i18n.AppLocaleController
import com.viel.aplayer.shared.settings.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Exercises Android resource resolution for feedback messages.
 * Robolectric keeps the test on the real Resources path so plural selection is verified where Toast copy is rendered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FeedbackMessageRenderingTest {

    @Test
    fun `quantity feedback renders singular and plural scan completion copy`() {
        val context = RuntimeEnvironment.getApplication()

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

        val one = FeedbackMessage.Quantity(R.plurals.feedback_sleep_timer_minutes, quantity = 1)
        val many = FeedbackMessage.Quantity(R.plurals.feedback_sleep_timer_minutes, quantity = 2)

        assertEquals("Sleep timer: 1 minute", one.render(context))
        assertEquals("Sleep timer: 2 minutes", many.render(context))
    }

    @Test
    fun `feedback render follows localized context resources`() {
        val context = RuntimeEnvironment.getApplication()
        val localizedContext = AppLocaleController.wrapContext(context, AppLanguage.Japanese)

        assertEquals(
            "\u518D\u751F\u901F\u5EA6\u3092\u30EA\u30BB\u30C3\u30C8\u3057\u307E\u3057\u305F",
            FeedbackMessages.playbackSpeedReset().render(localizedContext)
        )
    }

    @Test
    fun `playback blocking feedback appends stopped playback scope when title is available`() {
        val context = RuntimeEnvironment.getApplication()

        assertEquals(
            "Security blocked cleartext HTTP playback. Enable it in settings first.\nPlayback stopped: Book A",
            FeedbackMessages.playbackCleartextBlocked("Book A").render(context)
        )
    }

    @Test
    fun `semantic track unavailable feedback keeps routing payload while rendering stopped scope`() {
        val context = RuntimeEnvironment.getApplication()

        assertEquals(
            "The current track file is unavailable. Check whether you want to skip to another track.\nPlayback stopped: Book A",
            FeedbackMessages.playbackTrackUnavailable("book-1", 2, "Book A").render(context)
        )
    }
}
