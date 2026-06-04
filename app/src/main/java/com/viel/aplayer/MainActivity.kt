package com.viel.aplayer

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
import com.viel.aplayer.ui.navigation.APlayerApp
class MainActivity : ComponentActivity() {
    // Widget Overlay Request (Observe external widget requests using Compose state to handle activity re-entry via onNewIntent)
    private var shouldOpenPlayerOverlay by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cold Start Intent Parsing (Inspect initial Intent parameters early during cold starts to avoid losing widget signals)
        shouldOpenPlayerOverlay = intent?.getBooleanExtra(EXTRA_OPEN_PLAYER_OVERLAY, false) == true
        enableEdgeToEdge()
        
        // Disable Autofill Services (Disable autofill globally across the entire Activity hierarchy to bypass credentials popup)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS

        setContent {
            APlayerApp(
                openPlayerOverlayRequest = shouldOpenPlayerOverlay,
                onOpenPlayerOverlayConsumed = {
                    // Consume Widget Request (Reset overlay state to prevent repeated triggers on recomposition)
                    shouldOpenPlayerOverlay = false
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Re-entry Intent Interception (Process in-flight widget intents when Activity resides in background tasks)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER_OVERLAY, false)) {
            shouldOpenPlayerOverlay = true
        }
    }

    companion object {
        // Widget Intent Constants (Standardize Intent key names to prevent spelling drifting across modules)
        const val EXTRA_OPEN_PLAYER_OVERLAY = "com.viel.aplayer.extra.OPEN_PLAYER_OVERLAY"

        // Glance App Entry Mapping (Build activity Intent to launch back into the player overlay from the Glance widget)
        fun createOpenPlayerOverlayIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_PLAYER_OVERLAY, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
