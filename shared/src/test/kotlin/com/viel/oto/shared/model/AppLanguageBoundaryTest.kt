package com.viel.oto.shared.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Branch coverage for [AppLanguage.fromLocaleTag] that is not already exercised by
 * [AppSettingsModelTest] (which only covers null/System, zh-CN, zh-Hant-HK and en-US).
 *
 * Focus: every maintained language mapping, underscore normalization, prefix matching,
 * case insensitivity, whitespace trimming and the unknown-tag null fallback.
 */
class AppLanguageBoundaryTest {

    @Test
    fun `blank and whitespace only tags fall back to System`() {
        assertEquals(AppLanguage.System, AppLanguage.fromLocaleTag(""))
        assertEquals(AppLanguage.System, AppLanguage.fromLocaleTag("   "))
    }

    @Test
    fun `bare english maps to English`() {
        assertEquals(AppLanguage.English, AppLanguage.fromLocaleTag("en"))
    }

    @Test
    fun `maintained simple languages map by exact tag`() {
        assertEquals(AppLanguage.Japanese, AppLanguage.fromLocaleTag("ja"))
        assertEquals(AppLanguage.French, AppLanguage.fromLocaleTag("fr"))
        assertEquals(AppLanguage.German, AppLanguage.fromLocaleTag("de"))
        assertEquals(AppLanguage.Russian, AppLanguage.fromLocaleTag("ru"))
        assertEquals(AppLanguage.Spanish, AppLanguage.fromLocaleTag("es"))
        assertEquals(AppLanguage.Portuguese, AppLanguage.fromLocaleTag("pt"))
    }

    @Test
    fun `region suffixed languages match by prefix`() {
        assertEquals(AppLanguage.Japanese, AppLanguage.fromLocaleTag("ja-JP"))
        assertEquals(AppLanguage.French, AppLanguage.fromLocaleTag("fr-CA"))
        assertEquals(AppLanguage.German, AppLanguage.fromLocaleTag("de-AT"))
        assertEquals(AppLanguage.Russian, AppLanguage.fromLocaleTag("ru-RU"))
        assertEquals(AppLanguage.Spanish, AppLanguage.fromLocaleTag("es-MX"))
        assertEquals(AppLanguage.Portuguese, AppLanguage.fromLocaleTag("pt-BR"))
    }

    @Test
    fun `underscore separators are normalized to hyphens`() {
        assertEquals(AppLanguage.English, AppLanguage.fromLocaleTag("en_US"))
        assertEquals(AppLanguage.ChineseSimplified, AppLanguage.fromLocaleTag("zh_CN"))
        assertEquals(AppLanguage.ChineseSimplified, AppLanguage.fromLocaleTag("zh_Hans_CN"))
        assertEquals(AppLanguage.ChineseHongKong, AppLanguage.fromLocaleTag("zh_Hant_HK"))
        assertEquals(AppLanguage.ChineseTaiwan, AppLanguage.fromLocaleTag("zh_Hant_TW"))
    }

    @Test
    fun `surrounding whitespace is trimmed before matching`() {
        assertEquals(AppLanguage.French, AppLanguage.fromLocaleTag("  fr  "))
    }

    @Test
    fun `matching is case insensitive`() {
        assertEquals(AppLanguage.English, AppLanguage.fromLocaleTag("EN"))
        assertEquals(AppLanguage.Japanese, AppLanguage.fromLocaleTag("JA-jp"))
        assertEquals(AppLanguage.ChineseSimplified, AppLanguage.fromLocaleTag("ZH-hans"))
        assertEquals(AppLanguage.ChineseTaiwan, AppLanguage.fromLocaleTag("zh-tw"))
    }

    @Test
    fun `chinese script and region variants resolve to the expected language`() {
        assertEquals(AppLanguage.ChineseSimplified, AppLanguage.fromLocaleTag("zh"))
        assertEquals(AppLanguage.ChineseSimplified, AppLanguage.fromLocaleTag("zh-Hans"))
        assertEquals(AppLanguage.ChineseSimplified, AppLanguage.fromLocaleTag("zh-Hans-SG"))
        assertEquals(AppLanguage.ChineseHongKong, AppLanguage.fromLocaleTag("zh-HK"))
        assertEquals(AppLanguage.ChineseTaiwan, AppLanguage.fromLocaleTag("zh-TW"))
    }

    @Test
    fun `unknown tags return null`() {
        assertNull(AppLanguage.fromLocaleTag("xx"))
        assertNull(AppLanguage.fromLocaleTag("ko"))
        assertNull(AppLanguage.fromLocaleTag("zh-Hant"))
        assertNull(AppLanguage.fromLocaleTag("english"))
    }
}
