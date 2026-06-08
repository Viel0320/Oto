package com.viel.aplayer.media

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.viel.aplayer.R
import com.viel.aplayer.data.store.SeekStepSeconds

// Seek Step Presentation Resolver (Centralizes icons and accessibility labels for short seek controls)
// Notification, widget, and full-player controls all call this resolver so a step-size change cannot leave one surface with stale copy or artwork.
object SeekStepPresentation {
    @DrawableRes
    fun backwardIcon(step: SeekStepSeconds): Int =
        when (step) {
            SeekStepSeconds.Ten -> R.drawable.ic_replay_10
            SeekStepSeconds.Twenty -> R.drawable.ic_replay_20
            SeekStepSeconds.Thirty -> R.drawable.ic_replay_30
        }

    @DrawableRes
    fun forwardIcon(step: SeekStepSeconds): Int =
        when (step) {
            SeekStepSeconds.Ten -> R.drawable.ic_forward_10
            SeekStepSeconds.Twenty -> R.drawable.ic_forward_20
            SeekStepSeconds.Thirty -> R.drawable.ic_forward_30
        }

    @StringRes
    fun backwardLabel(step: SeekStepSeconds): Int =
        when (step) {
            SeekStepSeconds.Ten -> R.string.media_session_rewind_10
            SeekStepSeconds.Twenty -> R.string.media_session_rewind_20
            SeekStepSeconds.Thirty -> R.string.media_session_rewind_30
        }

    @StringRes
    fun forwardLabel(step: SeekStepSeconds): Int =
        when (step) {
            SeekStepSeconds.Ten -> R.string.media_session_forward_10
            SeekStepSeconds.Twenty -> R.string.media_session_forward_20
            SeekStepSeconds.Thirty -> R.string.media_session_forward_30
        }
}
