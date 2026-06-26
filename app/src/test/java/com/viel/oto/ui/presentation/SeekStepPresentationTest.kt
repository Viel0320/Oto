package com.viel.oto.ui.presentation

import com.viel.oto.shared.R
import com.viel.oto.shared.settings.SeekStepSeconds
import org.junit.Assert.assertEquals
import org.junit.Test

class SeekStepPresentationTest {
    @Test
    fun `backward seek steps map to expected icons and labels`() {
        assertEquals(R.drawable.ic_replay_10, SeekStepPresentation.backwardIcon(SeekStepSeconds.Ten))
        assertEquals(R.drawable.ic_replay_20, SeekStepPresentation.backwardIcon(SeekStepSeconds.Twenty))
        assertEquals(R.drawable.ic_replay_30, SeekStepPresentation.backwardIcon(SeekStepSeconds.Thirty))

        assertEquals(R.string.media_session_rewind_10, SeekStepPresentation.backwardLabel(SeekStepSeconds.Ten))
        assertEquals(R.string.media_session_rewind_20, SeekStepPresentation.backwardLabel(SeekStepSeconds.Twenty))
        assertEquals(R.string.media_session_rewind_30, SeekStepPresentation.backwardLabel(SeekStepSeconds.Thirty))
    }

    @Test
    fun `forward seek steps map to expected icons and labels`() {
        assertEquals(R.drawable.ic_forward_10, SeekStepPresentation.forwardIcon(SeekStepSeconds.Ten))
        assertEquals(R.drawable.ic_forward_20, SeekStepPresentation.forwardIcon(SeekStepSeconds.Twenty))
        assertEquals(R.drawable.ic_forward_30, SeekStepPresentation.forwardIcon(SeekStepSeconds.Thirty))

        assertEquals(R.string.media_session_forward_10, SeekStepPresentation.forwardLabel(SeekStepSeconds.Ten))
        assertEquals(R.string.media_session_forward_20, SeekStepPresentation.forwardLabel(SeekStepSeconds.Twenty))
        assertEquals(R.string.media_session_forward_30, SeekStepPresentation.forwardLabel(SeekStepSeconds.Thirty))
    }
}
