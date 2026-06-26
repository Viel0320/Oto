package com.viel.oto.ui.di

import com.viel.oto.ui.settings.SettingsRootFormatter
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * UI-owned settings presentation bindings.
 *
 * SettingsRootFormatter depends on Android resources and localized display copy, so it belongs
 * beside the settings screens instead of the application settings command/read-model module.
 */
object SettingsUiModule {

    val module: Module = module {
        single { SettingsRootFormatter(get()) }
    }
}
