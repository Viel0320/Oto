package com.viel.aplayer.ui.utils

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember

private const val NAVIGATION_CLICK_THROTTLE_MS = 180L

@Composable
fun rememberNavigationThrottle(): () -> Boolean {
    val lastNavigationClickAt = remember { mutableLongStateOf(0L) }
    return remember(lastNavigationClickAt) {
        {
            val now = SystemClock.elapsedRealtime()
            if (now - lastNavigationClickAt.longValue < NAVIGATION_CLICK_THROTTLE_MS) {
                false
            } else {
                lastNavigationClickAt.longValue = now
                true
            }
        }
    }
}
