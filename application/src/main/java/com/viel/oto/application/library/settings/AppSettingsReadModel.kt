package com.viel.oto.application.library.settings

import com.viel.oto.shared.model.AppSettings
import kotlinx.coroutines.flow.Flow

interface AppSettingsReadModel {
    val settingsFlow: Flow<AppSettings>
    val cachedSettings: AppSettings
}
