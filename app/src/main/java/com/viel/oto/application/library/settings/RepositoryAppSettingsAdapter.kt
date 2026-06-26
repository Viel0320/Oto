package com.viel.oto.application.library.settings

import com.viel.oto.data.AppSettingsRepository
import com.viel.oto.shared.settings.AppLanguage
import com.viel.oto.shared.settings.AppSettings
import com.viel.oto.shared.settings.GlassEffectMode
import com.viel.oto.shared.settings.HomeBookStatusFilter
import com.viel.oto.shared.settings.HomeFilter
import com.viel.oto.shared.settings.HomeSortDirection
import com.viel.oto.shared.settings.HomeSortRule
import com.viel.oto.shared.settings.HomeViewStyle
import com.viel.oto.shared.settings.SeekStepSeconds
import com.viel.oto.shared.settings.SleepMode
import com.viel.oto.shared.settings.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * Adapts the data-owned settings repository to application settings contracts.
 *
 * The repository stays a pure DataStore persistence module, while this adapter owns the
 * application-facing read and command contracts used by UI and scene orchestration.
 */
class RepositoryAppSettingsAdapter(
    private val repository: AppSettingsRepository
) : AppSettingsReadModel, AppSettingsCommands {
    override val settingsFlow: Flow<AppSettings>
        get() = repository.settingsFlow

    override val cachedSettings: AppSettings
        get() = repository.cachedSettings

    override suspend fun updateHomeFilter(filter: HomeFilter) =
        repository.updateHomeFilter(filter)

    override suspend fun updateHomeBookStatusFilter(filter: HomeBookStatusFilter) =
        repository.updateHomeBookStatusFilter(filter)

    override suspend fun updateHomeViewStyle(style: HomeViewStyle) =
        repository.updateHomeViewStyle(style)

    override suspend fun updateHomeSortRule(rule: HomeSortRule) =
        repository.updateHomeSortRule(rule)

    override suspend fun updateHomeSortDirection(direction: HomeSortDirection) =
        repository.updateHomeSortDirection(direction)

    override suspend fun updateGlobalSpeedEnabled(enabled: Boolean) =
        repository.updateGlobalSpeedEnabled(enabled)

    override suspend fun updateGlobalPlaybackSpeed(speed: Float) =
        repository.updateGlobalPlaybackSpeed(speed)

    override suspend fun updateChapterProgressMode(enabled: Boolean) =
        repository.updateChapterProgressMode(enabled)

    override suspend fun updateAllowInsecureTls(enabled: Boolean) =
        repository.updateAllowInsecureTls(enabled)

    override suspend fun updateCleartextTrafficAllowed(enabled: Boolean) =
        repository.updateCleartextTrafficAllowed(enabled)

    override suspend fun updateSkipSilenceEnabled(enabled: Boolean) =
        repository.updateSkipSilenceEnabled(enabled)

    override suspend fun updateSleepFadeOutEnabled(enabled: Boolean) =
        repository.updateSleepFadeOutEnabled(enabled)

    override suspend fun updateShakeToResetEnabled(enabled: Boolean) =
        repository.updateShakeToResetEnabled(enabled)

    override suspend fun updateSleepMode(mode: SleepMode) =
        repository.updateSleepMode(mode)

    override suspend fun updateGlassEffectMode(mode: GlassEffectMode) =
        repository.updateGlassEffectMode(mode)

    override suspend fun updateAutoRewindSeconds(seconds: Int) =
        repository.updateAutoRewindSeconds(seconds)

    override suspend fun updateSeekBackwardSeconds(step: SeekStepSeconds) =
        repository.updateSeekBackwardSeconds(step)

    override suspend fun updateSeekForwardSeconds(step: SeekStepSeconds) =
        repository.updateSeekForwardSeconds(step)

    override suspend fun updateSubtitleSyncOffsetMs(offsetMs: Long) =
        repository.updateSubtitleSyncOffsetMs(offsetMs)

    override suspend fun updateLastPlaybackInterrupted(interrupted: Boolean) =
        repository.updateLastPlaybackInterrupted(interrupted)

    override suspend fun updateNotificationAvoidanceEnabled(enabled: Boolean) =
        repository.updateNotificationAvoidanceEnabled(enabled)

    override suspend fun updateThemeMode(mode: ThemeMode) =
        repository.updateThemeMode(mode)

    override suspend fun updateAppLanguage(language: AppLanguage) =
        repository.updateAppLanguage(language)

    override suspend fun updateDynamicColorEnabled(enabled: Boolean) =
        repository.updateDynamicColorEnabled(enabled)

    override suspend fun updateAmoledEnabled(enabled: Boolean) =
        repository.updateAmoledEnabled(enabled)

    override suspend fun updatePlaybackBufferMaxBytes(bytes: Long) =
        repository.updatePlaybackBufferMaxBytes(bytes)

    override suspend fun updateDownloadWifiOnly(enabled: Boolean) =
        repository.updateDownloadWifiOnly(enabled)
}
