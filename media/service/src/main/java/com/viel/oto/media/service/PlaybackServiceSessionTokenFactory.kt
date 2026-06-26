package com.viel.oto.media.service

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.SessionToken
import com.viel.oto.media.PlaybackSessionTokenFactory

/**
 * App-service implementation that points playback controllers at the current Media3 service.
 *
 * This adapter is intentionally outside playback core because it names the concrete Android
 * service class that `:media:service` will own in the next extraction step.
 */
class PlaybackServiceSessionTokenFactory : PlaybackSessionTokenFactory {
    override fun createSessionToken(context: Context): SessionToken {
        val appContext = context.applicationContext
        return SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
    }
}
