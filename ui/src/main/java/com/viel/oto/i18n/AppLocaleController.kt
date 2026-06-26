package com.viel.oto.i18n

import android.annotation.SuppressLint
import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.viel.oto.logger.SecureLog
import com.viel.oto.shared.model.AppLanguage

object AppLocaleController {
    private const val TAG = "AppLocaleController"
    private const val SUPPORTED_LOCALE_TAGS = "en,zh-Hans-CN,zh-Hant-HK,zh-Hant-TW,ja,fr,de,ru,es,pt"

    fun ensurePlatformLocaleConfig(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        runCatching {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            localeManager.overrideLocaleConfig = LocaleConfig(
                LocaleList.forLanguageTags(SUPPORTED_LOCALE_TAGS)
            )
        }.onFailure { error ->
            SecureLog.warn(TAG, "Failed to publish app locale config", error)
        }
    }

    fun applyPlatformLocale(context: Context, language: AppLanguage) {
        ensurePlatformLocaleConfig(context)
        runCatching {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            localeManager.applicationLocales = LocaleList.forLanguageTags(language.localeTag)
        }.onFailure { error ->
            SecureLog.warn(TAG, "Failed to apply app locale ${language.localeTag}", error)
        }
    }

    fun readPlatformLanguage(context: Context): AppLanguage? {
        return runCatching {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            val firstLocaleTag = localeManager.applicationLocales
                .toLanguageTags()
                .substringBefore(",")
                .trim()
            AppLanguage.fromLocaleTag(firstLocaleTag) ?: AppLanguage.System
        }.getOrElse { error ->
            SecureLog.warn(TAG, "Failed to read platform app locale", error)
            null
        }
    }

    fun resolveEffectiveLanguage(context: Context, storedLanguage: AppLanguage): AppLanguage {
        val platformLanguage = readPlatformLanguage(context)
        return when {
            platformLanguage != null && platformLanguage != AppLanguage.System -> platformLanguage
            storedLanguage != AppLanguage.System -> storedLanguage
            else -> AppLanguage.System
        }
    }

    @SuppressLint("AppBundleLocaleChanges")
    fun wrapContext(context: Context, language: AppLanguage): Context {
        if (language == AppLanguage.System) return context
        val localeList = LocaleList.forLanguageTags(language.localeTag)
        val localizedConfiguration = Configuration(context.resources.configuration).apply {
            setLocales(localeList)
        }
        return context.createConfigurationContext(localizedConfiguration)
    }
}
