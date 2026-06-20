package com.viel.aplayer.event.feedback

/**
 * Playback Control Feedback Facts (Command-owner fact factory for speed and sleep-timer outcomes)
 *
 * Speed and sleep-timer selection feedback is produced by the command owner after the control state
 * changes, not by the leaf composable. Both use the playback control category and context; rapid taps
 * collapse to the final value through the delivery policy's provisional hold, so these facts are
 * [FeedbackLifecycle.PROVISIONAL]. Resource keys are unchanged from the previous UI-side messages.
 */
object PlaybackControlFeedbackFacts {

    /**
     * Playback Speed Changed (Final selected speed after a tap settles)
     *
     * A speed of 1.0 reports the reset copy so the user-visible wording matches the previous behavior.
     */
    fun playbackSpeedChanged(speed: Float): FeedbackFact =
        if (speed == 1.0f) {
            playbackSpeedReset()
        } else {
            playbackControlFact(
                message = FeedbackMessages.playbackSpeedChanged(formatPlaybackSpeed(speed)),
                topic = FeedbackTopic.PlaybackSpeed
            )
        }

    /** Playback Speed Reset (Speed returned to the 1.0 baseline). */
    fun playbackSpeedReset(): FeedbackFact =
        playbackControlFact(
            message = FeedbackMessages.playbackSpeedReset(),
            topic = FeedbackTopic.PlaybackSpeed
        )

    /**
     * Sleep Timer Selected (Final selected sleep-timer mode after a tap settles)
     *
     * The numeric selection follows the existing UI mapping: 0 off, -1 five-second test, -2 end of
     * chapter, otherwise a minute count.
     */
    fun sleepTimerSelected(minutes: Int): FeedbackFact {
        val message = when (minutes) {
            0 -> FeedbackMessages.sleepTimerOff()
            -1 -> FeedbackMessages.sleepTimerFiveSeconds()
            -2 -> FeedbackMessages.sleepTimerEndOfChapter()
            else -> FeedbackMessages.sleepTimerMinutes(minutes)
        }
        return playbackControlFact(message, FeedbackTopic.SleepTimer)
    }

    /**
     * Sleep Shake Extended To Next Chapter (Shake moved the sleep target to the next chapter end)
     *
     * The timer state actually changed, so this is a completed outcome.
     */
    fun sleepShakeExtendedToNextChapter(): FeedbackFact =
        sleepTimerFact(FeedbackMessages.sleepShakeExtendedToNextChapter(), FeedbackSeverity.COMPLETED)

    /** Sleep Shake Countdown Reset (Shake reset the active minute countdown; state changed). */
    fun sleepShakeCountdownReset(): FeedbackFact =
        sleepTimerFact(FeedbackMessages.sleepShakeCountdownReset(), FeedbackSeverity.COMPLETED)

    /** Sleep Shake Test Countdown Reset (Shake reset the diagnostic countdown; state changed). */
    fun sleepShakeTestCountdownReset(): FeedbackFact =
        sleepTimerFact(FeedbackMessages.sleepShakeTestCountdownReset(), FeedbackSeverity.COMPLETED)

    /**
     * Sleep Shake No Next Chapter (Already the final chapter, shake produced no state change)
     *
     * Nothing changed, so this is a plain informational hint rather than a completed outcome.
     */
    fun sleepShakeNoNextChapter(): FeedbackFact =
        sleepTimerFact(FeedbackMessages.sleepShakeNoNextChapter(), FeedbackSeverity.HINT)

    /** Sleep Tracking Countdown Started (System detected stable sleep and began the countdown). */
    fun sleepTrackingCountdownStarted(): FeedbackFact =
        sleepTimerFact(FeedbackMessages.sleepTrackingCountdownStarted(), FeedbackSeverity.STARTED)

    /** Sleep Motion Tracking Paused (Motion paused tracking; informational state notice). */
    fun sleepMotionTrackingPaused(): FeedbackFact =
        sleepTimerFact(FeedbackMessages.sleepMotionTrackingPaused(), FeedbackSeverity.HINT)

    /** Sleep Tracking Paused By Activity (Body activity paused tracking; informational state notice). */
    fun sleepTrackingPausedByActivity(): FeedbackFact =
        sleepTimerFact(FeedbackMessages.sleepTrackingPausedByActivity(), FeedbackSeverity.HINT)

    /** Sleep Motion Tracking Resumed (Stillness resumed tracking; informational state notice). */
    fun sleepMotionTrackingResumed(): FeedbackFact =
        sleepTimerFact(FeedbackMessages.sleepMotionTrackingResumed(), FeedbackSeverity.HINT)

    /**
     * Playback Finished Shutdown Scheduled (Service will close after the completed-queue grace period)
     *
     * A session-level control outcome, not tied to a specific book; final and informational.
     */
    fun playbackFinishedShutdownScheduled(delaySeconds: Int): FeedbackFact =
        FeedbackFact(
            message = FeedbackMessages.playbackFinishedShutdownScheduled(delaySeconds),
            outcome = FeedbackOutcome(
                identity = FeedbackAggregationIdentity(
                    category = FeedbackCategory.PLAYBACK_CONTROL,
                    topic = FeedbackTopic.PlaybackSessionShutdown,
                    context = FeedbackContext.PlaybackControl
                ),
                severity = FeedbackSeverity.HINT,
                lifecycle = FeedbackLifecycle.FINAL
            )
        )

    private fun sleepTimerFact(message: FeedbackMessage, severity: FeedbackSeverity): FeedbackFact =
        FeedbackFact(
            message = message,
            outcome = FeedbackOutcome(
                identity = FeedbackAggregationIdentity(
                    category = FeedbackCategory.PLAYBACK_CONTROL,
                    topic = FeedbackTopic.SleepTimer,
                    context = FeedbackContext.PlaybackControl
                ),
                severity = severity,
                lifecycle = FeedbackLifecycle.FINAL
            )
        )

    private fun playbackControlFact(message: FeedbackMessage, topic: FeedbackTopic): FeedbackFact =
        FeedbackFact(
            message = message,
            outcome = FeedbackOutcome(
                identity = FeedbackAggregationIdentity(
                    category = FeedbackCategory.PLAYBACK_CONTROL,
                    topic = topic,
                    context = FeedbackContext.PlaybackControl
                ),
                severity = FeedbackSeverity.COMPLETED,
                lifecycle = FeedbackLifecycle.PROVISIONAL
            )
        )

    private fun formatPlaybackSpeed(speed: Float): String {
        // Keep feedback arguments copy-neutral: trim trailing zero/dot from whole-number speeds while
        // preserving fractional values such as 0.75 or 1.25.
        val text = speed.toString()
        return text.trimEnd('0').trimEnd('.')
    }
}
