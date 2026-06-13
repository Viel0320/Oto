package com.viel.aplayer.application.library.settings

import com.viel.aplayer.data.store.AppLanguage
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.HomeBookStatusFilter
import com.viel.aplayer.data.store.HomeFilter
import com.viel.aplayer.data.store.HomeSortDirection
import com.viel.aplayer.data.store.HomeSortRule
import com.viel.aplayer.data.store.HomeViewStyle
import com.viel.aplayer.data.store.SeekStepSeconds
import com.viel.aplayer.data.store.SleepMode
import com.viel.aplayer.data.store.ThemeMode

// Title: AppSettingsCommands Definition (Provides mutation signatures for updating app settings preferences)
// Decouples UI controllers from concrete DataStore writes by exposing only required command interfaces.
interface AppSettingsCommands {
    suspend fun updateHomeFilter(filter: HomeFilter)
    suspend fun updateHomeBookStatusFilter(filter: HomeBookStatusFilter)
    suspend fun updateHomeViewStyle(style: HomeViewStyle)
    suspend fun updateHomeSortRule(rule: HomeSortRule)
    suspend fun updateHomeSortDirection(direction: HomeSortDirection)
    suspend fun updateGlobalSpeedEnabled(enabled: Boolean)
    suspend fun updateGlobalPlaybackSpeed(speed: Float)
    suspend fun updateChapterProgressMode(enabled: Boolean)
    suspend fun updateAllowInsecureTls(enabled: Boolean)
    suspend fun updateCleartextTrafficAllowed(enabled: Boolean)
    suspend fun updateSkipSilenceEnabled(enabled: Boolean)
    suspend fun updateSleepFadeOutEnabled(enabled: Boolean)
    suspend fun updateShakeToResetEnabled(enabled: Boolean)
    suspend fun updateSleepMode(mode: SleepMode)
    suspend fun updateGlassEffectMode(mode: GlassEffectMode)
    suspend fun updateAutoRewindSeconds(seconds: Int)
    suspend fun updateSeekBackwardSeconds(step: SeekStepSeconds)
    suspend fun updateSeekForwardSeconds(step: SeekStepSeconds)
    suspend fun updateLastPlaybackInterrupted(interrupted: Boolean)
    suspend fun updateNotificationAvoidanceEnabled(enabled: Boolean)
    suspend fun updateThemeMode(mode: ThemeMode)
    suspend fun updateAppLanguage(language: AppLanguage)
    suspend fun updateDynamicColorEnabled(enabled: Boolean)
}
