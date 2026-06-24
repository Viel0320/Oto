package com.viel.aplayer.application.library.settings

import com.viel.aplayer.shared.settings.AppLanguage
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.shared.settings.HomeBookStatusFilter
import com.viel.aplayer.shared.settings.HomeFilter
import com.viel.aplayer.shared.settings.HomeSortDirection
import com.viel.aplayer.shared.settings.HomeSortRule
import com.viel.aplayer.shared.settings.HomeViewStyle
import com.viel.aplayer.shared.settings.SeekStepSeconds
import com.viel.aplayer.shared.settings.SleepMode
import com.viel.aplayer.shared.settings.ThemeMode

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
    /**
     * Persists the app-wide subtitle cue offset used by playback subtitle matching.
     *
     * The offset changes only presentation timing in player UI; subtitle files, playback progress,
     * and media clock state remain owned by their existing playback and library boundaries.
     */
    suspend fun updateSubtitleSyncOffsetMs(offsetMs: Long)
    suspend fun updateLastPlaybackInterrupted(interrupted: Boolean)
    suspend fun updateNotificationAvoidanceEnabled(enabled: Boolean)
    suspend fun updateThemeMode(mode: ThemeMode)
    suspend fun updateAppLanguage(language: AppLanguage)
    suspend fun updateDynamicColorEnabled(enabled: Boolean)
    suspend fun updateAmoledEnabled(enabled: Boolean)
    suspend fun updatePlaybackBufferMaxBytes(bytes: Long)
    suspend fun updateDownloadWifiOnly(enabled: Boolean)
}
