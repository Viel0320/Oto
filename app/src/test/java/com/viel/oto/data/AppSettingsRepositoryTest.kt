package com.viel.oto.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppSettingsRepositoryTest {

    @Test
    fun `settings flow is initialized before cache collector starts`() {
        val source = repoFile("app/src/main/java/com/viel/oto/data/AppSettingsRepository.kt").readText()
        val settingsFlowDeclaration = source.indexOf("val settingsFlow: Flow<AppSettings>")
        val collectorInitBlock = source.indexOf("init {")

        assertTrue("settingsFlow declaration must exist.", settingsFlowDeclaration >= 0)
        assertTrue("cache collector init block must exist.", collectorInitBlock >= 0)
        assertTrue(
            "settingsFlow must be declared before the cache collector init block.",
            settingsFlowDeclaration < collectorInitBlock
        )
    }

    @Test
    fun `cached settings collector starts after repository construction`() = runBlocking {
        val dataStore = createSettingsDataStore(testName = "cached-settings")
        val repository = AppSettingsRepository.createForTesting(dataStore)

        repository.updateDynamicColorEnabled(false)

        assertTrue(
            "cachedSettings must receive DataStore updates after repository construction.",
            eventually { !repository.cachedSettings.isDynamicColorEnabled }
        )
    }

    @Test
    fun `settings flow normalizes restored numeric playback settings`() = runBlocking {
        val dataStore = createSettingsDataStore(testName = "read-bounds")
        val repository = AppSettingsRepository.createForTesting(dataStore)

        val restoredCases = listOf(
            RestoredPlaybackSettings(
                speed = Float.NaN,
                rewindSeconds = -5,
                subtitleOffsetMs = -45_000L,
                expectedSpeed = 1.0f,
                expectedRewindSeconds = 0,
                expectedSubtitleOffsetMs = -30_000L
            ),
            RestoredPlaybackSettings(
                speed = -0.5f,
                rewindSeconds = 99,
                subtitleOffsetMs = 45_000L,
                expectedSpeed = 1.0f,
                expectedRewindSeconds = 30,
                expectedSubtitleOffsetMs = 30_000L
            ),
            RestoredPlaybackSettings(
                speed = 99.0f,
                rewindSeconds = Int.MAX_VALUE,
                subtitleOffsetMs = 1_250L,
                expectedSpeed = 1.0f,
                expectedRewindSeconds = 30,
                expectedSubtitleOffsetMs = 1_250L
            )
        )

        restoredCases.forEachIndexed { index, restored ->
            dataStore.edit { preferences ->
                preferences[globalPlaybackSpeedKey] = restored.speed
                preferences[autoRewindSecondsKey] = restored.rewindSeconds
                preferences[subtitleSyncOffsetMsKey] = restored.subtitleOffsetMs
            }

            val settings = repository.settingsFlow.first()

            assertEquals("case $index speed", restored.expectedSpeed, settings.globalPlaybackSpeed, 0.0f)
            assertEquals("case $index rewind", restored.expectedRewindSeconds, settings.autoRewindSeconds)
            assertEquals("case $index subtitle offset", restored.expectedSubtitleOffsetMs, settings.subtitleSyncOffsetMs)
        }
    }

    @Test
    fun `settings flow migrates and normalizes restored playback buffer settings`() = runBlocking {
        val dataStore = createSettingsDataStore(testName = "read-playback-buffer")
        dataStore.edit { preferences ->
            preferences[legacyPlaybackCacheMaxBytesKey] = 11L * 1024L * 1024L * 1024L
            preferences[legacyPlaybackBufferDurationMsKey] = 999_999
            preferences[isDownloadWifiOnlyKey] = false
        }
        val repository = AppSettingsRepository.createForTesting(dataStore)

        val settings = repository.settingsFlow.first()

        assertEquals(com.viel.oto.shared.settings.AppSettings.DEFAULT_PLAYBACK_BUFFER_MAX_BYTES, settings.playbackBufferMaxBytes)
        assertEquals(false, settings.isDownloadWifiOnly)
        assertTrue(
            "legacy disk cache key should migrate into the new playback buffer key and stale duration should be removed",
            eventually {
                val preferences = dataStore.data.first()
                preferences[legacyPlaybackCacheMaxBytesKey] == null &&
                    preferences[legacyPlaybackBufferDurationMsKey] == null &&
                    preferences[playbackBufferMaxBytesKey] == com.viel.oto.shared.settings.AppSettings.DEFAULT_PLAYBACK_BUFFER_MAX_BYTES
            }
        )
    }

    @Test
    fun `settings flow normalizes restored filter settings`() = runBlocking {
        val dataStore = createSettingsDataStore(testName = "read-filters")
        val repository = AppSettingsRepository.createForTesting(dataStore)

        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("home_filter")] = "Finished"
            preferences[stringPreferencesKey("home_book_status_filter")] = "Partial"
        }

        val settings = repository.settingsFlow.first()
        assertEquals(com.viel.oto.shared.settings.HomeFilter.Finished, settings.homeFilter)
        assertEquals(com.viel.oto.shared.settings.HomeBookStatusFilter.Partial, settings.homeBookStatusFilter)

        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("home_filter")] = "InvalidFilterName"
            preferences[stringPreferencesKey("home_book_status_filter")] = "InvalidStatusName"
        }

        val fallbackSettings = repository.settingsFlow.first()
        assertEquals(com.viel.oto.shared.settings.HomeFilter.NotStarted, fallbackSettings.homeFilter)
        assertEquals(com.viel.oto.shared.settings.HomeBookStatusFilter.All, fallbackSettings.homeBookStatusFilter)
    }

    @Test
    fun `write methods persist enum filter settings`() = runBlocking {
        val dataStore = createSettingsDataStore(testName = "write-filters")
        val repository = AppSettingsRepository.createForTesting(dataStore)

        repository.updateHomeFilter(com.viel.oto.shared.settings.HomeFilter.InProgress)
        repository.updateHomeBookStatusFilter(com.viel.oto.shared.settings.HomeBookStatusFilter.Unavailable)

        val preferences = dataStore.data.first()
        assertEquals("InProgress", preferences[stringPreferencesKey("home_filter")])
        assertEquals("Unavailable", preferences[stringPreferencesKey("home_book_status_filter")])
    }

    @Test
    fun `write methods persist normalized numeric playback settings`() = runBlocking {
        val dataStore = createSettingsDataStore(testName = "write-bounds")
        val repository = AppSettingsRepository.createForTesting(dataStore)

        repository.updateGlobalPlaybackSpeed(1.5f)
        repository.updateAutoRewindSeconds(12)
        repository.updateSubtitleSyncOffsetMs(1_250L)

        val validPreferences = dataStore.data.first()
        assertEquals(1.5f, validPreferences[globalPlaybackSpeedKey] ?: 0.0f, 0.0f)
        assertEquals(12, validPreferences[autoRewindSecondsKey])
        assertEquals(1_250L, validPreferences[subtitleSyncOffsetMsKey])

        repository.updateGlobalPlaybackSpeed(Float.NaN)
        repository.updateAutoRewindSeconds(-4)
        repository.updateSubtitleSyncOffsetMs(-99_000L)

        val lowPreferences = dataStore.data.first()
        assertEquals(1.0f, lowPreferences[globalPlaybackSpeedKey] ?: 0.0f, 0.0f)
        assertEquals(0, lowPreferences[autoRewindSecondsKey])
        assertEquals(-30_000L, lowPreferences[subtitleSyncOffsetMsKey])

        repository.updateGlobalPlaybackSpeed(99.0f)
        repository.updateAutoRewindSeconds(99)
        repository.updateSubtitleSyncOffsetMs(99_000L)

        val highPreferences = dataStore.data.first()
        assertEquals(1.0f, highPreferences[globalPlaybackSpeedKey] ?: 0.0f, 0.0f)
        assertEquals(30, highPreferences[autoRewindSecondsKey])
        assertEquals(30_000L, highPreferences[subtitleSyncOffsetMsKey])
    }

    @Test
    fun `write methods persist playback buffer settings`() = runBlocking {
        val dataStore = createSettingsDataStore(testName = "write-playback-buffer")
        val repository = AppSettingsRepository.createForTesting(dataStore)

        repository.updatePlaybackBufferMaxBytes(128L * 1024L * 1024L)
        repository.updateDownloadWifiOnly(false)

        val validPreferences = dataStore.data.first()
        assertEquals(128L * 1024L * 1024L, validPreferences[playbackBufferMaxBytesKey])
        assertEquals(false, validPreferences[isDownloadWifiOnlyKey])

        repository.updatePlaybackBufferMaxBytes(1L)

        val fallbackPreferences = dataStore.data.first()
        assertEquals(com.viel.oto.shared.settings.AppSettings.DEFAULT_PLAYBACK_BUFFER_MAX_BYTES, fallbackPreferences[playbackBufferMaxBytesKey])
    }

    private fun createSettingsDataStore(testName: String) =
        PreferenceDataStoreFactory.create(
            produceFile = {
                File(createTempDirectory(prefix = "app-settings-repository-$testName").toFile(), "app_settings.preferences_pb")
            }
        )

    private fun repoFile(path: String): File {
        val candidates = listOf(File(path), File("../$path"))
        return candidates.firstOrNull { file -> file.exists() }
            ?: error("Could not locate $path from ${File(".").absolutePath}")
    }

    private suspend fun eventually(timeoutMs: Long = 1_000, predicate: suspend () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            delay(10)
        }
        return predicate()
    }

    private data class RestoredPlaybackSettings(
        val speed: Float,
        val rewindSeconds: Int,
        val subtitleOffsetMs: Long,
        val expectedSpeed: Float,
        val expectedRewindSeconds: Int,
        val expectedSubtitleOffsetMs: Long
    )

    companion object {
        private val globalPlaybackSpeedKey = floatPreferencesKey("global_playback_speed")

        private val autoRewindSecondsKey = intPreferencesKey("auto_rewind_seconds")

        private val subtitleSyncOffsetMsKey = longPreferencesKey("subtitle_sync_offset_ms")

        private val legacyPlaybackCacheMaxBytesKey = longPreferencesKey("playback_cache_max_bytes")

        private val playbackBufferMaxBytesKey = longPreferencesKey("playback_buffer_max_bytes")

        private val legacyPlaybackBufferDurationMsKey = intPreferencesKey("playback_buffer_duration_ms")

        private val isDownloadWifiOnlyKey = booleanPreferencesKey("is_download_wifi_only")
    }
}
