package com.viel.oto.library.vfs.sourceProvider.webdav

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.viel.oto.data.AppSettingsRepository
import com.viel.oto.data.webdav.WebDavCredentialStore
import com.viel.oto.data.webdav.webDavCredentialDataStore
import com.viel.oto.shared.settings.AppSettings
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory

private val settingsStoreCounter = AtomicInteger()

/**
 * Creates an isolated settings repository for WebDAV protocol tests.
 * Each repository uses its own DataStore file so cleartext and TLS policy mutations cannot leak across test cases.
 */
internal fun testAppSettingsRepository(testName: String): AppSettingsRepository {
    val uniqueName = "$testName-${settingsStoreCounter.incrementAndGet()}"
    val tempDir = createTempDirectory(uniqueName).toFile()
    return AppSettingsRepository.createForTesting(
        PreferenceDataStoreFactory.create(
            produceFile = { File(tempDir, "settings.preferences_pb") }
        )
    )
}

internal fun testWebDavSettings(
    cleartextAllowed: Boolean = true,
    insecureTlsAllowed: Boolean = false
): AppSettings =
    AppSettings(
        isCleartextTrafficAllowed = cleartextAllowed,
        isAllowInsecureTls = insecureTlsAllowed
    )

/**
 * Builds a WebDAV provider with deterministic network policy settings.
 *
 * Module-level tests cannot rely on the app composition root or asynchronous DataStore cache warmup,
 * so they pass policy state directly while keeping credential persistence injectable.
 */
internal fun testWebDavSourceProvider(
    context: Context,
    settings: AppSettings = testWebDavSettings(),
    webDavCredentialStore: WebDavCredentialStore = WebDavCredentialStore(context.applicationContext.webDavCredentialDataStore)
): WebDavSourceProvider =
    WebDavSourceProvider(
        context = context,
        settingsProvider = { settings },
        webDavCredentialStore = webDavCredentialStore
    )

internal fun testWebDavConnectionTester(
    settings: AppSettings = testWebDavSettings()
): WebDavConnectionTester =
    WebDavConnectionTester(
        appSettingsRepository = testAppSettingsRepository("webdav-connection"),
        settingsProvider = { settings }
    )
