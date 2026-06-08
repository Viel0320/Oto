package com.viel.aplayer.ui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.viel.aplayer.R
import com.viel.aplayer.data.store.AppLanguage

// App Language Option Order (Expose one stable language list for rows and dialogs)
// System default stays first so users can quickly hand locale control back to Android.
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

// App Language Label Resolution (Map stored locale choices to translated display labels)
// Keeping resource IDs here prevents DataStore enums from depending on Android resources or presentation text.
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
