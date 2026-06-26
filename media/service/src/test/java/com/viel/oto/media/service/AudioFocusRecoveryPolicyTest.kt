package com.viel.oto.media.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusRecoveryPolicyTest {
    @Test
    fun `denied focus request keeps playback paused after gain`() {
        val policy = AudioFocusRecoveryPolicy()

        policy.onTransientLoss(isPlayerPlaying = true)
        val action = policy.onFocusGain { false }

        assertEquals(AudioFocusPlaybackAction.None, action)
        assertTrue(policy.isHoldingFocusLossPause)
    }

    @Test
    fun `transient focus loss without gain does not request playback recovery`() {
        val policy = AudioFocusRecoveryPolicy()

        val action = policy.onTransientLoss(isPlayerPlaying = true)

        assertEquals(AudioFocusPlaybackAction.Pause, action)
        assertTrue(policy.isHoldingFocusLossPause)
    }

    @Test
    fun `focus gain with granted request resumes paused playback`() {
        val policy = AudioFocusRecoveryPolicy()

        policy.onTransientLoss(isPlayerPlaying = true)
        val action = policy.onFocusGain { true }

        assertEquals(AudioFocusPlaybackAction.Play, action)
        assertEquals(AudioFocusPlaybackAction.None, policy.onFocusGain { true })
    }
}
