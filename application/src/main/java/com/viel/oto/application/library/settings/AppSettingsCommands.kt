package com.viel.oto.application.library.settings

import com.viel.oto.shared.model.AppLanguage
import com.viel.oto.shared.model.GlassEffectMode
import com.viel.oto.shared.model.HomeBookStatusFilter
import com.viel.oto.shared.model.HomeFilter
import com.viel.oto.shared.model.HomeSortDirection
import com.viel.oto.shared.model.HomeSortRule
import com.viel.oto.shared.model.HomeViewStyle
import com.viel.oto.shared.model.SeekStepSeconds
import com.viel.oto.shared.model.SleepMode
import com.viel.oto.shared.model.ThemeMode

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
    /**
     * Persists whether active playback should apply the narrator voice enhancement processor.
     *
     * The command remains a pure settings boundary; PlaybackService observes the resulting
     * AppSettings value and owns all Media3 processor details.
     */
    suspend fun updateVoiceEnhancementEnabled(enabled: Boolean)
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
