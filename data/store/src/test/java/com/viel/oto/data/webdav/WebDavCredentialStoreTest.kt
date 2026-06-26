package com.viel.oto.data.webdav

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WebDavCredentialStoreTest {

    @Test
    fun `save get and delete should round trip credential data`() = runBlocking {
        val store = testStore("webdav-round-trip")

        val saved = store.save(
            username = "reader",
            password = "secret",
            credentialId = "webdav-credential"
        )

        assertEquals("webdav-credential", saved.id)
        assertEquals("reader", store.get("webdav-credential")?.username)
        assertEquals("secret", store.get("webdav-credential")?.password)

        store.delete("webdav-credential")

        assertNull(store.get("webdav-credential"))
    }

    /**
     * Creates a new preferences file for each test so DataStore's single-writer invariant remains intact.
     */
    private fun testStore(testName: String): WebDavCredentialStore {
        val tempDir = createTempDirectory(testName).toFile()
        return WebDavCredentialStore.createForTesting(
            PreferenceDataStoreFactory.create(
                produceFile = { File(tempDir, "webdav_credentials.preferences_pb") }
            )
        )
    }
}
