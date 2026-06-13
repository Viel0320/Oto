package com.viel.aplayer.application.library.settings

import com.viel.aplayer.data.store.AppSettings
import kotlinx.coroutines.flow.Flow

// Title: AppSettingsReadModel Definition (Provides query flow and cached access to app settings independently of persistence layer)
// Decouples presentation ViewModels from the concrete AppSettingsRepository DataStore implementation.
interface AppSettingsReadModel {
    val settingsFlow: Flow<AppSettings>
    val cachedSettings: AppSettings
}
