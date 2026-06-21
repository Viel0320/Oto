package com.viel.aplayer.di.graph

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.application.library.settings.AppSettingsCommands
import com.viel.aplayer.application.library.settings.AppSettingsReadModel
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.store.SearchHistoryStore

/**
 * Owns process-wide data stores, database, and settings.
 * Gives higher graphs one stable place to obtain durable app data dependencies without carrying playback lifecycle objects.
 */
@UnstableApi
internal class DataGraph(val context: Context) {
    val database: AppDatabase by lazy {
        AppDatabase.getInstance(context)
    }

    val searchHistoryStore: SearchHistoryStore by lazy {
        SearchHistoryStore.getInstance(context)
    }

    val settingsRepository: AppSettingsRepository by lazy {
        AppSettingsRepository.getInstance(context)
    }

    val settingsReadModel: AppSettingsReadModel get() = settingsRepository
    val settingsCommands: AppSettingsCommands get() = settingsRepository
}
