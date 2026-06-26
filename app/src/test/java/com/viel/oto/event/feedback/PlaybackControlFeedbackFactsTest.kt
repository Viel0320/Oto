package com.viel.oto.event.feedback

import com.viel.oto.shared.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the speed and sleep-timer outcome contract.
 *
 * Verifies the migrated command-owner facts keep the original resource keys, classify under the
 * playback control identity, and stay provisional so rapid taps collapse to the final value.
 */
class PlaybackControlFeedbackFactsTest {

    @Test
    fun `speed change uses playback control identity and provisional lifecycle`() {
        val fact = PlaybackControlFeedbackFacts.playbackSpeedChanged(1.25f)

        val outcome = fact.outcome
        assertEquals(FeedbackCategory.PLAYBACK_CONTROL, outcome.identity.category)
        assertEquals(FeedbackTopic.PlaybackSpeed, outcome.identity.topic)
        assertEquals(FeedbackContext.PlaybackControl, outcome.identity.context)
        assertEquals(FeedbackSeverity.COMPLETED, outcome.severity)
        assertEquals(FeedbackLifecycle.PROVISIONAL, outcome.lifecycle)
        assertEquals(FeedbackTaskInstance.SingleShot, outcome.taskInstance)
    }

    @Test
    fun `speed change keeps the original resource key and trims whole-number formatting`() {
        val whole = PlaybackControlFeedbackFacts.playbackSpeedChanged(2.0f).message as FeedbackMessage.Resource
        assertEquals(R.string.feedback_playback_speed_changed, whole.resId)
        assertEquals(listOf<Any>("2"), whole.args)

        val fractional = PlaybackControlFeedbackFacts.playbackSpeedChanged(0.75f).message as FeedbackMessage.Resource
        assertEquals(listOf<Any>("0.75"), fractional.args)
    }

    @Test
    fun `speed of one reports the reset copy`() {
        val fromChanged = PlaybackControlFeedbackFacts.playbackSpeedChanged(1.0f).message as FeedbackMessage.Resource
        val fromReset = PlaybackControlFeedbackFacts.playbackSpeedReset().message as FeedbackMessage.Resource

        assertEquals(R.string.feedback_playback_speed_reset, fromChanged.resId)
        assertEquals(fromReset.resId, fromChanged.resId)
    }

    @Test
    fun `sleep timer selection maps each mode to its original resource key`() {
        assertEquals(
            R.string.feedback_sleep_timer_off,
            (PlaybackControlFeedbackFacts.sleepTimerSelected(0).message as FeedbackMessage.Resource).resId
        )
        assertEquals(
            R.string.feedback_sleep_timer_five_seconds,
            (PlaybackControlFeedbackFacts.sleepTimerSelected(-1).message as FeedbackMessage.Resource).resId
        )
        assertEquals(
            R.string.feedback_sleep_timer_end_of_chapter,
            (PlaybackControlFeedbackFacts.sleepTimerSelected(-2).message as FeedbackMessage.Resource).resId
        )
        val minutes = PlaybackControlFeedbackFacts.sleepTimerSelected(30).message as FeedbackMessage.Quantity
        assertEquals(R.plurals.feedback_sleep_timer_minutes, minutes.resId)
        assertEquals(30, minutes.quantity)
    }

    @Test
    fun `sleep timer selection uses playback control identity`() {
        val outcome = PlaybackControlFeedbackFacts.sleepTimerSelected(15).outcome
        assertEquals(FeedbackCategory.PLAYBACK_CONTROL, outcome.identity.category)
        assertEquals(FeedbackTopic.SleepTimer, outcome.identity.topic)
        assertEquals(FeedbackContext.PlaybackControl, outcome.identity.context)
        assertEquals(FeedbackLifecycle.PROVISIONAL, outcome.lifecycle)
    }

    @Test
    fun `shake state changers are completed while no-next-chapter is a hint`() {
        assertEquals(
            FeedbackSeverity.COMPLETED,
            PlaybackControlFeedbackFacts.sleepShakeExtendedToNextChapter().outcome.severity
        )
        assertEquals(
            FeedbackSeverity.COMPLETED,
            PlaybackControlFeedbackFacts.sleepShakeCountdownReset().outcome.severity
        )
        assertEquals(
            FeedbackSeverity.COMPLETED,
            PlaybackControlFeedbackFacts.sleepShakeTestCountdownReset().outcome.severity
        )
        assertEquals(
            FeedbackSeverity.HINT,
            PlaybackControlFeedbackFacts.sleepShakeNoNextChapter().outcome.severity
        )
    }

    @Test
    fun `tracking countdown start is started while pause-resume notices are hints`() {
        assertEquals(
            FeedbackSeverity.STARTED,
            PlaybackControlFeedbackFacts.sleepTrackingCountdownStarted().outcome.severity
        )
        assertEquals(
            FeedbackSeverity.HINT,
            PlaybackControlFeedbackFacts.sleepMotionTrackingPaused().outcome.severity
        )
        assertEquals(
            FeedbackSeverity.HINT,
            PlaybackControlFeedbackFacts.sleepTrackingPausedByActivity().outcome.severity
        )
        assertEquals(
            FeedbackSeverity.HINT,
            PlaybackControlFeedbackFacts.sleepMotionTrackingResumed().outcome.severity
        )
    }

    @Test
    fun `all sleep facts share the single sleep timer identity`() {
        val identities = listOf(
            PlaybackControlFeedbackFacts.sleepShakeExtendedToNextChapter(),
            PlaybackControlFeedbackFacts.sleepShakeNoNextChapter(),
            PlaybackControlFeedbackFacts.sleepTrackingCountdownStarted(),
            PlaybackControlFeedbackFacts.sleepMotionTrackingPaused()
        ).map { it.outcome.identity }

        identities.forEach { identity ->
            assertEquals(FeedbackCategory.PLAYBACK_CONTROL, identity.category)
            assertEquals(FeedbackTopic.SleepTimer, identity.topic)
            assertEquals(FeedbackContext.PlaybackControl, identity.context)
        }
    }
}
