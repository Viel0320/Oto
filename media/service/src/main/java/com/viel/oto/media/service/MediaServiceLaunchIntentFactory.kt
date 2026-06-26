package com.viel.oto.media.service

import android.content.Context
import android.content.Intent

/**
 * Supplies app-shell launch intents without letting the service module name the Activity class.
 *
 * MediaSession notifications and download notifications need stable entrypoints back into the app,
 * but the service module must not depend on `MainActivity` while it is compiled as an Android
 * library. The app module owns the concrete implementation.
 */
interface MediaServiceLaunchIntentFactory {
    fun openPlayerOverlayIntent(context: Context): Intent

    fun openDownloadManagementIntent(context: Context): Intent

    fun openAppIntent(context: Context): Intent
}
