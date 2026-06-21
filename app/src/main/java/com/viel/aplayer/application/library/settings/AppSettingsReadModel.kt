package com.viel.aplayer.application.library.settings

import com.viel.aplayer.shared.settings.AppSettings
import kotlinx.coroutines.flow.Flow

interface AppSettingsReadModel {
    val settingsFlow: Flow<AppSettings>
    val cachedSettings: AppSettings
}
