package com.viel.oto.abs.availability

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.viel.oto.abs.auth.AbsCredentialStore
import com.viel.oto.abs.net.AbsApiClient
import com.viel.oto.abs.net.AbsApiError
import com.viel.oto.abs.net.AbsAuthInterceptor
import com.viel.oto.abs.net.AbsTokenRefreshClient
import com.viel.oto.abs.net.AbsTokenRefreshResult
import com.viel.oto.abs.net.dto.AbsAuthorizeResponseDto
import com.viel.oto.abs.net.dto.AbsAuthorizedUserDto
import com.viel.oto.abs.net.dto.AbsLibraryDto
import com.viel.oto.abs.net.dto.AbsLibraryItemDto
import com.viel.oto.abs.net.dto.AbsLibraryItemsResponseDto
import com.viel.oto.abs.net.dto.AbsPlaybackSessionDto
import com.viel.oto.abs.net.dto.AbsPlayRequestDto
import com.viel.oto.abs.net.dto.AbsStatusDto
import com.viel.oto.abs.sync.AbsConnectionTester
import com.viel.oto.abs.vfs.AbsSourceProvider
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.shared.model.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class AbsLibraryAvailabilityGatewayTest {

    @Test
    fun `missing credential maps to AUTH_FAILED`() = runBlocking {
        // Store has no credential saved for this id, so get() returns null.
        val store = createCredentialStore(saveCredential = false)
        val gateway = createGateway(
            store = store,
            apiClient = FakeApiClient(bookLibraryIds = listOf("lib-1")),
            settings = AppSettings()
        )

        val result = gateway.checkRoot(absRoot(basePath = "lib-1"))

        assertEquals(AudiobookSchema.AvailabilityStatus.AUTH_FAILED, result.status)
        assertEquals("MISSING_ABS_CREDENTIAL", result.errorCode)
    }

    @Test
    fun `blocked cleartext http maps via UnsafeNetworkPolicyViolation to UNKNOWN`() = runBlocking {
        // http on a non-LAN host with cleartext disabled triggers the policy throw before any network call.
        val store = createCredentialStore(saveCredential = true, baseUrl = "http://books.example.com")
        val gateway = createGateway(
            store = store,
            apiClient = FakeApiClient(bookLibraryIds = listOf("lib-1")),
            settings = AppSettings(isCleartextTrafficAllowed = false)
        )

        val result = gateway.checkRoot(absRoot(basePath = "lib-1"))

        assertEquals(AudiobookSchema.AvailabilityStatus.UNKNOWN, result.status)
        assertEquals("UnsafeNetworkPolicyViolation", result.errorCode)
    }

    @Test
    fun `matching library id maps to AVAILABLE`() = runBlocking {
        val store = createCredentialStore(saveCredential = true)
        val gateway = createGateway(
            store = store,
            apiClient = FakeApiClient(bookLibraryIds = listOf("other", "lib-1")),
            settings = AppSettings()
        )

        val result = gateway.checkRoot(absRoot(basePath = "lib-1"))

        assertEquals(AudiobookSchema.AvailabilityStatus.AVAILABLE, result.status)
        assertNull(result.errorCode)
    }

    @Test
    fun `absent library id maps to NOT_FOUND`() = runBlocking {
        val store = createCredentialStore(saveCredential = true)
        val gateway = createGateway(
            store = store,
            apiClient = FakeApiClient(bookLibraryIds = listOf("other-lib")),
            settings = AppSettings()
        )

        val result = gateway.checkRoot(absRoot(basePath = "lib-1"))

        assertEquals(AudiobookSchema.AvailabilityStatus.NOT_FOUND, result.status)
        assertEquals(AudiobookSchema.AvailabilityStatus.NOT_FOUND.name, result.errorCode)
        assertEquals("ABS library not found", result.message)
    }

    @Test
    fun `non-book libraries are filtered out before id match`() = runBlocking {
        // A podcast library with the matching id must not satisfy the book-library check.
        val store = createCredentialStore(saveCredential = true)
        val gateway = createGateway(
            store = store,
            apiClient = FakeApiClient(
                bookLibraryIds = emptyList(),
                extraLibraries = listOf(AbsLibraryDto(id = "lib-1", mediaType = "podcast"))
            ),
            settings = AppSettings()
        )

        val result = gateway.checkRoot(absRoot(basePath = "lib-1"))

        assertEquals(AudiobookSchema.AvailabilityStatus.NOT_FOUND, result.status)
    }

    @Test
    fun `AbsApiError is mapped using its availability status and code`() = runBlocking {
        val store = createCredentialStore(saveCredential = true)
        val gateway = createGateway(
            store = store,
            apiClient = FakeApiClient(
                statusError = AbsApiError(
                    code = "UNSUPPORTED_SERVER_VERSION",
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.UNSUPPORTED,
                    message = "too old"
                )
            ),
            settings = AppSettings()
        )

        val result = gateway.checkRoot(absRoot(basePath = "lib-1"))

        assertEquals(AudiobookSchema.AvailabilityStatus.UNSUPPORTED, result.status)
        assertEquals("UNSUPPORTED_SERVER_VERSION", result.errorCode)
        assertEquals("too old", result.message)
    }

    private fun createGateway(
        store: AbsCredentialStore,
        apiClient: AbsApiClient,
        settings: AppSettings
    ): AbsLibraryAvailabilityGateway =
        AbsLibraryAvailabilityGateway(
            credentialStore = store,
            connectionTester = AbsConnectionTester(apiClient),
            sourceProvider = createSourceProvider(store),
            settingsProvider = { settings }
        )

    // Provide every constructor arg so the KoinComponent never resolves injected dependencies.
    private fun createSourceProvider(store: AbsCredentialStore): AbsSourceProvider =
        AbsSourceProvider(
            credentialStore = store,
            settingsProvider = { AppSettings() },
            tokenRefreshClient = object : AbsTokenRefreshClient {
                override suspend fun refreshToken(credentialId: String): AbsTokenRefreshResult =
                    AbsTokenRefreshResult.Failed
            },
            client = OkHttpClient.Builder().addInterceptor(AbsAuthInterceptor(store)).build()
        )

    private fun createCredentialStore(
        saveCredential: Boolean,
        baseUrl: String = "https://books.example.com"
    ): AbsCredentialStore {
        val tempDir = createTempDirectory(prefix = "abs-availability").toFile()
        val store = AbsCredentialStore.createForTesting(
            PreferenceDataStoreFactory.create(
                produceFile = { File(tempDir, "credentials.preferences_pb") }
            )
        )
        if (saveCredential) {
            runBlocking {
                store.save(baseUrl = baseUrl, token = "token-1", credentialId = CREDENTIAL_ID)
            }
        }
        return store
    }

    private fun absRoot(basePath: String) = LibraryRootEntity(
        id = "root-1",
        sourceType = AudiobookSchema.LibrarySourceType.ABS,
        sourceUri = "https://books.example.com",
        basePath = basePath,
        credentialId = CREDENTIAL_ID,
        displayName = "ABS"
    )

    private class FakeApiClient(
        private val bookLibraryIds: List<String> = emptyList(),
        private val extraLibraries: List<AbsLibraryDto> = emptyList(),
        private val statusError: Throwable? = null
    ) : AbsApiClient {
        override suspend fun status(baseUrl: String): AbsStatusDto {
            statusError?.let { throw it }
            return AbsStatusDto(serverVersion = "2.35.1", isInit = true)
        }

        override suspend fun login(baseUrl: String, username: String, password: String) =
            throw UnsupportedOperationException()

        override suspend fun authorize(baseUrl: String, token: String): AbsAuthorizeResponseDto =
            AbsAuthorizeResponseDto(user = AbsAuthorizedUserDto(id = "user-1", username = "demo", token = token))

        override suspend fun getLibraries(baseUrl: String, token: String): List<AbsLibraryDto> =
            bookLibraryIds.map { id -> AbsLibraryDto(id = id, mediaType = "book") } + extraLibraries

        override suspend fun getLibraryItemsMinified(baseUrl: String, token: String, libraryId: String): AbsLibraryItemsResponseDto =
            AbsLibraryItemsResponseDto()

        override suspend fun batchGetItems(baseUrl: String, token: String, itemIds: List<String>): List<AbsLibraryItemDto> =
            emptyList()

        override suspend fun openPlaybackSession(baseUrl: String, token: String, itemId: String, request: AbsPlayRequestDto): AbsPlaybackSessionDto =
            throw UnsupportedOperationException()

        override suspend fun syncSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = Unit

        override suspend fun closeSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double) = Unit
    }

    private companion object {
        private const val CREDENTIAL_ID = "cred-1"
    }
}
