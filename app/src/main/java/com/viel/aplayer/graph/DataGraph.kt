package com.viel.aplayer.graph

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.db.AppDatabase

/**
 * Data Graph (Owns process-wide data stores, database, and settings)
 * Gives higher graphs one stable place to obtain durable app data dependencies without carrying playback lifecycle objects.
 */
@UnstableApi
internal class DataGraph(val context: Context) {
    val database: AppDatabase by lazy {
        // Database Initialization: Instantiates the single room database coordinator safely.
        AppDatabase.getInstance(context)
    }

    val searchHistoryStore: com.viel.aplayer.data.store.SearchHistoryStore by lazy {
        // Search Store Initialization: Prepares the settings lookup and search logs DataStore.
        com.viel.aplayer.data.store.SearchHistoryStore.getInstance(context)
    }

    val settingsRepository: AppSettingsRepository by lazy {
        // App Settings Repository: Accesses the application configuration options.
        AppSettingsRepository.getInstance(context)
    }
}
