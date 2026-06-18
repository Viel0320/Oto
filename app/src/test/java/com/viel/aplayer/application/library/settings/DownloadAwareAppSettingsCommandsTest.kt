package com.viel.aplayer.application.library.settings

import com.viel.aplayer.application.download.DownloadRuntimeGateway
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadAwareAppSettingsCommandsTest {
    @Test
    fun `wifi setting should persist without resolving cold download runtime`() = runBlocking {
        val delegate = RecordingSettingsCommands()
        val gateway = RecordingDownloadRuntimeGateway()
        val commands = DownloadAwareAppSettingsCommands(
            delegate = delegate,
            downloadRuntimeGatewayProvider = {
                error("Cold settings change must not resolve download runtime")
            },
            isDownloadRuntimeInitialized = { false }
        )

        commands.updateDownloadWifiOnly(false)

        // Cold Runtime Policy Update (Persist settings without starting Media3 download runtime)
        // This protects settings-only interactions from constructing DownloadManager or foreground service state.
        assertEquals(listOf(false), delegate.persistedWifiOnlyValues)
        assertEquals(emptyList<Boolean>(), gateway.requirementUpdates)
    }

    @Test
    fun `wifi setting should update requirements when runtime is already initialized`() = runBlocking {
        val delegate = RecordingSettingsCommands()
        val gateway = RecordingDownloadRuntimeGateway()
        val commands = DownloadAwareAppSettingsCommands(
            delegate = delegate,
            downloadRuntimeGatewayProvider = { gateway },
            isDownloadRuntimeInitialized = { true }
        )

        commands.updateDownloadWifiOnly(true)

        // Hot Runtime Policy Update (Mirror persisted WiFi policy into active Media3 requirements)
        // Running downloads observe the new network rule without rebuilding DownloadGraph or losing queue state.
        assertEquals(listOf(true), delegate.persistedWifiOnlyValues)
        assertEquals(listOf(true), gateway.requirementUpdates)
    }

    private class RecordingSettingsCommands : AppSettingsCommands {
        val persistedWifiOnlyValues = mutableListOf<Boolean>()

        override suspend fun updateDownloadWifiOnly(enabled: Boolean) {
            persistedWifiOnlyValues += enabled
        }

        override suspend fun updateHomeFilter(filter: HomeFilter) = Unit
        override suspend fun updateHomeBookStatusFilter(filter: HomeBookStatusFilter) = Unit
        override suspend fun updateHomeViewStyle(style: HomeViewStyle) = Unit
        override suspend fun updateHomeSortRule(rule: HomeSortRule) = Unit
        override suspend fun updateHomeSortDirection(direction: HomeSortDirection) = Unit
        override suspend fun updateGlobalSpeedEnabled(enabled: Boolean) = Unit
        override suspend fun updateGlobalPlaybackSpeed(speed: Float) = Unit
        override suspend fun updateChapterProgressMode(enabled: Boolean) = Unit
        override suspend fun updateAllowInsecureTls(enabled: Boolean) = Unit
        override suspend fun updateCleartextTrafficAllowed(enabled: Boolean) = Unit
        override suspend fun updateSkipSilenceEnabled(enabled: Boolean) = Unit
        override suspend fun updateSleepFadeOutEnabled(enabled: Boolean) = Unit
        override suspend fun updateShakeToResetEnabled(enabled: Boolean) = Unit
        override suspend fun updateSleepMode(mode: SleepMode) = Unit
        override suspend fun updateGlassEffectMode(mode: GlassEffectMode) = Unit
        override suspend fun updateAutoRewindSeconds(seconds: Int) = Unit
        override suspend fun updateSeekBackwardSeconds(step: SeekStepSeconds) = Unit
        override suspend fun updateSeekForwardSeconds(step: SeekStepSeconds) = Unit
        override suspend fun updateLastPlaybackInterrupted(interrupted: Boolean) = Unit
        override suspend fun updateNotificationAvoidanceEnabled(enabled: Boolean) = Unit
        override suspend fun updateThemeMode(mode: ThemeMode) = Unit
        override suspend fun updateAppLanguage(language: AppLanguage) = Unit
        override suspend fun updateDynamicColorEnabled(enabled: Boolean) = Unit
        override suspend fun updateAmoledEnabled(enabled: Boolean) = Unit
        override suspend fun updatePlaybackBufferMaxBytes(bytes: Long) = Unit
    }

    private class RecordingDownloadRuntimeGateway : DownloadRuntimeGateway {
        val requirementUpdates = mutableListOf<Boolean>()

        override fun addDownload(request: androidx.media3.exoplayer.offline.DownloadRequest) = Unit
        override fun removeDownload(fileId: String) = Unit
        override fun pauseDownloads() = Unit
        override fun resumeDownloads() = Unit
        override fun setStopReason(fileId: String, reason: Int) = Unit
        override fun updateRequirements(wifiOnly: Boolean) {
            requirementUpdates += wifiOnly
        }
    }
}
