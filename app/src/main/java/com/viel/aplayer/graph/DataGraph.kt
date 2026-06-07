package com.viel.aplayer.graph

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.db.AppDatabase

/**
 * Data Graph (Owns process-wide data stores, database, settings, and singleton managers)
 * Gives higher graphs one stable place to obtain durable app data dependencies.
 */
@UnstableApi
internal class DataGraph(val context: Context) {
    val database: AppDatabase by lazy {
        // Database Initialization: Instantiates the single room database coordinator safely.
        AppDatabase.getInstance(context)
    }

    val playbackManager: com.viel.aplayer.media.PlaybackManager by lazy {
        // Playback Singleton Initialization: Connects media lifecycle events across system interfaces.
        com.viel.aplayer.media.PlaybackManager.getInstance(context)
    }

    val searchHistoryStore: com.viel.aplayer.data.store.SearchHistoryStore by lazy {
        // Search Store Initialization: Prepares the settings lookup and search logs DataStore.
        com.viel.aplayer.data.store.SearchHistoryStore.getInstance(context)
    }

    val autoRewindManager: com.viel.aplayer.media.AutoRewindManager by lazy {
        // Rewind Manager Initialization: Orchestrates the self-healing progress tracking logic.
        com.viel.aplayer.media.AutoRewindManager.getInstance(context)
    }

    val settingsRepository: AppSettingsRepository by lazy {
        // App Settings Repository: Accesses the application configuration options.
        AppSettingsRepository.getInstance(context)
    }
}
