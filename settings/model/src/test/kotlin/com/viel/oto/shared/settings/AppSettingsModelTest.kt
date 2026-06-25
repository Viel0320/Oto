package com.viel.oto.shared.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Protects persisted setting value compatibility inside the extracted settings model module.
 *
 * These checks stay close to the pure model so later Android, Room, or UI module moves cannot
 * accidentally change stored enum parsing behavior while only touching presentation code.
 */
class AppSettingsModelTest {

    @Test
    fun localeTagsResolveMaintainedLanguagesAndSystemFallback() {
        assertEquals(AppLanguage.System, AppLanguage.fromLocaleTag(null))
        assertEquals(AppLanguage.ChineseSimplified, AppLanguage.fromLocaleTag("zh-CN"))
        assertEquals(AppLanguage.ChineseHongKong, AppLanguage.fromLocaleTag("zh-Hant-HK"))
        assertEquals(AppLanguage.English, AppLanguage.fromLocaleTag("en-US"))
    }

    @Test
    fun storedHomeBookStatusFallsBackToAllForUnknownValues() {
        assertEquals(HomeBookStatusFilter.Ready, HomeBookStatusFilter.fromStoredName("Ready"))
        assertEquals(HomeBookStatusFilter.All, HomeBookStatusFilter.fromStoredName("retired-value"))
    }
}
