package com.viel.aplayer.i18n

import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.viel.aplayer.data.store.AppLanguage
import com.viel.aplayer.logger.SecureLog

object AppLocaleController {
    private const val TAG = "AppLocaleController"
    private const val SUPPORTED_LOCALE_TAGS = "en,zh-Hans-CN,zh-Hant-HK,zh-Hant-TW,ja,fr,de,ru,es,pt"

    // Platform Locale Config Publication (Publish supported languages to Android Settings at runtime)
    // Android 14+ persists override LocaleConfig and uses it for the system per-app language screen, which repairs stale installs or OEM builds that miss the manifest-backed config.
    fun ensurePlatformLocaleConfig(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        runCatching {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            localeManager.overrideLocaleConfig = LocaleConfig(
                LocaleList.forLanguageTags(SUPPORTED_LOCALE_TAGS)
            )
        }.onFailure { error ->
            // Release Warning Boundary (Sanitize platform locale publication failures)
            // OEM locale APIs can return platform exception text, so retained warnings use the secure emitter.
            SecureLog.warn(TAG, "Failed to publish app locale config", error)
        }
    }

    // Platform Locale Application (Write explicit locale choices into Android per-app language storage)
    // Android 13+ owns process-wide app locale changes through LocaleManager; older devices use the Compose context fallback instead.
    fun applyPlatformLocale(context: Context, language: AppLanguage) {
        ensurePlatformLocaleConfig(context)
        runCatching {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            localeManager.applicationLocales = LocaleList.forLanguageTags(language.localeTag)
        }.onFailure { error ->
            // Release Warning Boundary (Sanitize platform locale application failures)
            // The selected locale tag is safe context, while SecureLog removes sensitive text from the Throwable chain.
            SecureLog.warn(TAG, "Failed to apply app locale ${language.localeTag}", error)
        }
    }

    // Platform Locale Readback (Observe Android-managed per-app locale selection)
    // Returning null on pre-Android 13 keeps old devices bound to the locally persisted AppLanguage value.
    fun readPlatformLanguage(context: Context): AppLanguage? {
        return runCatching {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            val firstLocaleTag = localeManager.applicationLocales
                .toLanguageTags()
                .substringBefore(",")
                .trim()
            AppLanguage.fromLocaleTag(firstLocaleTag) ?: AppLanguage.System
        }.getOrElse { error ->
            // Release Warning Boundary (Sanitize platform locale readback failures)
            // Readback warnings remain useful for OEM compatibility triage without preserving raw exception payloads.
            SecureLog.warn(TAG, "Failed to read platform app locale", error)
            null
        }
    }

    // Effective Language Resolution (Prefer Android platform selection when it exists)
    // This allows users changing language from system settings to see the same locale inside the in-app language row.
    fun resolveEffectiveLanguage(context: Context, storedLanguage: AppLanguage): AppLanguage {
        val platformLanguage = readPlatformLanguage(context)
        return when {
            platformLanguage != null && platformLanguage != AppLanguage.System -> platformLanguage
            storedLanguage != AppLanguage.System -> storedLanguage
            else -> AppLanguage.System
        }
    }

    // Localized Context Wrapper (Provide resource lookup fallback for Android 12L)
    // Compose reads string resources from LocalContext, so a configuration context lets pre-LocaleManager devices update UI copy after DataStore changes.
    fun wrapContext(context: Context, language: AppLanguage): Context {
        if (language == AppLanguage.System) return context
        val localeList = LocaleList.forLanguageTags(language.localeTag)
        val localizedConfiguration = Configuration(context.resources.configuration).apply {
            setLocales(localeList)
        }
        return context.createConfigurationContext(localizedConfiguration)
    }
}
