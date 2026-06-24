package com.viel.oto

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.viel.oto.ui.navigation.OtoApp
class MainActivity : ComponentActivity() {
    private var shouldOpenPlayerOverlay by mutableStateOf(false)
    private var shouldOpenDownloadManagement by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shouldOpenPlayerOverlay = intent?.getBooleanExtra(EXTRA_OPEN_PLAYER_OVERLAY, false) == true
        shouldOpenDownloadManagement = intent?.getBooleanExtra(EXTRA_OPEN_DOWNLOAD_MANAGEMENT, false) == true
        enableEdgeToEdge()

        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS

        setContent {
            OtoApp(
                openPlayerOverlayRequest = shouldOpenPlayerOverlay,
                onOpenPlayerOverlayConsumed = {
                    shouldOpenPlayerOverlay = false
                },
                openDownloadManagementRequest = shouldOpenDownloadManagement,
                onOpenDownloadManagementConsumed = {
                    shouldOpenDownloadManagement = false
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER_OVERLAY, false)) {
            shouldOpenPlayerOverlay = true
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_DOWNLOAD_MANAGEMENT, false)) {
            shouldOpenDownloadManagement = true
        }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER_OVERLAY = "com.viel.oto.extra.OPEN_PLAYER_OVERLAY"
        const val EXTRA_OPEN_DOWNLOAD_MANAGEMENT = "com.viel.oto.extra.OPEN_DOWNLOAD_MANAGEMENT"

        fun createOpenPlayerOverlayIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_PLAYER_OVERLAY, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun createOpenDownloadManagementIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_DOWNLOAD_MANAGEMENT, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
