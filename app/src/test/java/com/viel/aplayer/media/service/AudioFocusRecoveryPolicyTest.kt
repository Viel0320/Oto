package com.viel.aplayer.media.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusRecoveryPolicyTest {
    @Test
    fun `denied focus request keeps playback paused after gain`() {
        val policy = AudioFocusRecoveryPolicy()

        policy.onTransientLoss(isPlayerPlaying = true)
        val action = policy.onFocusGain { false }

        // Denied Focus Recovery Gate (Blocks playback restart when Android refuses the follow-up focus request)
        // The passive pause remains held so a later granted gain can still recover without timer-based playback.
        assertEquals(AudioFocusPlaybackAction.None, action)
        assertTrue(policy.isHoldingFocusLossPause)
    }

    @Test
    fun `transient focus loss without gain does not request playback recovery`() {
        val policy = AudioFocusRecoveryPolicy()

        val action = policy.onTransientLoss(isPlayerPlaying = true)

        // Gain-Only Recovery Contract (Transient loss only pauses and records intent)
        // No playback action is emitted until Android reports AUDIOFOCUS_GAIN and the focus request succeeds.
        assertEquals(AudioFocusPlaybackAction.Pause, action)
        assertTrue(policy.isHoldingFocusLossPause)
    }

    @Test
    fun `focus gain with granted request resumes paused playback`() {
        val policy = AudioFocusRecoveryPolicy()

        policy.onTransientLoss(isPlayerPlaying = true)
        val action = policy.onFocusGain { true }

        // Granted Focus Recovery Gate (Allows playback only after explicit gain and successful focus acquisition)
        // The passive pause flag clears after resume so repeated gain callbacks do not replay playback commands.
        assertEquals(AudioFocusPlaybackAction.Play, action)
        assertEquals(AudioFocusPlaybackAction.None, policy.onFocusGain { true })
    }
}
