package com.viel.oto.di

import android.content.Context
import android.content.Intent
import com.viel.oto.MainActivity
import com.viel.oto.media.service.MediaServiceLaunchIntentFactory

/**
 * App-shell launch-intent adapter for media service notifications.
 *
 * The extracted service module requests semantic app destinations through
 * MediaServiceLaunchIntentFactory; this adapter is the only place that names MainActivity.
 */
class AppMediaServiceLaunchIntentFactory : MediaServiceLaunchIntentFactory {
    override fun openPlayerOverlayIntent(context: Context): Intent =
        MainActivity.createOpenPlayerOverlayIntent(context)

    override fun openDownloadManagementIntent(context: Context): Intent =
        MainActivity.createOpenDownloadManagementIntent(context)

    override fun openAppIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
}
