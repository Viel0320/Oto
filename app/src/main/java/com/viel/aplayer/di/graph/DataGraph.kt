package com.viel.aplayer.di.graph

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.application.library.settings.AppSettingsCommands
import com.viel.aplayer.application.library.settings.AppSettingsReadModel
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.store.SearchHistoryStore

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

    val searchHistoryStore: SearchHistoryStore by lazy {
        // Search Store Initialization: Prepares the settings lookup and search logs DataStore.
        SearchHistoryStore.getInstance(context)
    }

    val settingsRepository: AppSettingsRepository by lazy {
        // App Settings Repository: Accesses the application configuration options.
        AppSettingsRepository.getInstance(context)
    }

    // Title: Settings Abstractions Exposure (Exposes settings read and command interfaces from DataGraph)
    // Exposes interfaces to other graphs and DI layers instead of the concrete AppSettingsRepository class.
    val settingsReadModel: AppSettingsReadModel get() = settingsRepository
    val settingsCommands: AppSettingsCommands get() = settingsRepository
}
