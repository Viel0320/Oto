package com.viel.oto.media.service

import android.content.Context
import com.viel.oto.shared.settings.SeekStepSeconds

/**
 * Supplies localized MediaSession command labels and app-owned icon resource ids.
 *
 * CommandButton accepts runtime resource ids, but this module must not compile against app `R`.
 * The app adapter resolves the concrete strings and drawables.
 */
interface PlaybackCommandPresentation {
    fun rewindTitle(context: Context, step: SeekStepSeconds): String

    fun rewindIcon(step: SeekStepSeconds): Int

    fun forwardTitle(context: Context, step: SeekStepSeconds): String

    fun forwardIcon(step: SeekStepSeconds): Int

    fun bookmarkTitle(context: Context): String

    fun bookmarkIcon(): Int
}
