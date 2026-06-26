package com.viel.oto.ui.presentation

import android.content.Context
import com.viel.oto.R
import com.viel.oto.media.service.PlaybackCommandPresentation
import com.viel.oto.shared.settings.SeekStepSeconds

/**
 * Resolves MediaSession command presentation from app resources.
 *
 * Resource ids are app-owned, but the extracted service module can still use the resulting ids at
 * runtime because notifications are rendered inside the final application package.
 */
class AppPlaybackCommandPresentation : PlaybackCommandPresentation {
    override fun rewindTitle(context: Context, step: SeekStepSeconds): String =
        context.getString(SeekStepPresentation.backwardLabel(step))

    override fun rewindIcon(step: SeekStepSeconds): Int =
        SeekStepPresentation.backwardIcon(step)

    override fun forwardTitle(context: Context, step: SeekStepSeconds): String =
        context.getString(SeekStepPresentation.forwardLabel(step))

    override fun forwardIcon(step: SeekStepSeconds): Int =
        SeekStepPresentation.forwardIcon(step)

    override fun bookmarkTitle(context: Context): String =
        context.getString(R.string.bookmark_add_title)

    override fun bookmarkIcon(): Int =
        R.drawable.ic_bookmark_add
}
