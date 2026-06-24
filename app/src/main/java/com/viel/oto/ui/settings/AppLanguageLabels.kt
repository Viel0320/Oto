package com.viel.oto.ui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.viel.oto.R
import com.viel.oto.shared.settings.AppLanguage

val AppLanguageOptions: List<AppLanguage> = listOf(
    AppLanguage.System,
    AppLanguage.English,
    AppLanguage.ChineseSimplified,
    AppLanguage.ChineseHongKong,
    AppLanguage.ChineseTaiwan,
    AppLanguage.Japanese,
    AppLanguage.French,
    AppLanguage.German,
    AppLanguage.Russian,
    AppLanguage.Spanish,
    AppLanguage.Portuguese
)

@StringRes
fun appLanguageLabelRes(language: AppLanguage): Int =
    when (language) {
        AppLanguage.System -> R.string.settings_language_system_default
        AppLanguage.English -> R.string.settings_language_english
        AppLanguage.ChineseSimplified -> R.string.settings_language_chinese_simplified
        AppLanguage.ChineseHongKong -> R.string.settings_language_chinese_hong_kong
        AppLanguage.ChineseTaiwan -> R.string.settings_language_chinese_taiwan
        AppLanguage.Japanese -> R.string.settings_language_japanese
        AppLanguage.French -> R.string.settings_language_french
        AppLanguage.German -> R.string.settings_language_german
        AppLanguage.Russian -> R.string.settings_language_russian
        AppLanguage.Spanish -> R.string.settings_language_spanish
        AppLanguage.Portuguese -> R.string.settings_language_portuguese
    }

@Composable
fun appLanguageLabel(language: AppLanguage): String =
    stringResource(appLanguageLabelRes(language))
