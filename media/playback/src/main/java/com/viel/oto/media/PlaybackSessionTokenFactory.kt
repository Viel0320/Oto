package com.viel.oto.media

import android.content.Context
import androidx.media3.session.SessionToken

/**
 * Creates the Media3 session token used by playback controllers.
 *
 * The playback module owns controller lifecycle, but the concrete Android service class remains an
 * app/service concern until `:media:service` is extracted.
 */
fun interface PlaybackSessionTokenFactory {
    fun createSessionToken(context: Context): SessionToken
}
