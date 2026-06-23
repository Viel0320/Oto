package com.viel.aplayer.library.vfs.sourceProvider.webdav

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.viel.aplayer.data.AppSettingsRepository
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
