package com.viel.oto.application.usecase

import com.viel.oto.abs.auth.AbsCredentialStore
import com.viel.oto.abs.net.AbsApiClient
import com.viel.oto.abs.net.AbsUrlResolver
import com.viel.oto.abs.sync.AbsConnectionTestResult
import com.viel.oto.abs.sync.AbsConnectionTester
import com.viel.oto.data.dao.LibraryRootDao
import com.viel.oto.data.root.LibraryRootGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Caches successful testing credentials.
 * The snapshot lives in the settings application layer so ViewModel can retain it as ephemeral UI-adjacent state without owning ABS authentication logic.
 */
data class AbsConnectionReuseSnapshot(
    val baseUrl: String,
    val username: String,
    val token: String,
    val connection: AbsConnectionTestResult
)

data class AbsConnectionTestOutcome(
    val result: AbsConnectionTestResult,
    val snapshot: AbsConnectionReuseSnapshot
)

data class AbsServerSaveOutcome(
    val rootId: String,
    val snapshot: AbsConnectionReuseSnapshot
)

/**
 * Owns login, token reuse, credential persistence, and root registration.
 * SettingsViewModel logs attempts and renders state, while this use case hides ABS client, credential store, and edit-mode root lookup details.
 */
class AbsSettingsConnectionUseCase(
    private val apiClient: AbsApiClient,
    private val connectionTester: AbsConnectionTester,
    private val credentialStore: AbsCredentialStore,
    private val libraryRootDao: LibraryRootDao,
    private val libraryRootGateway: LibraryRootGateway,
    private val libraryRootManagementUseCase: LibraryRootManagementUseCase,
    private val maintenanceUseCase: SettingsLibraryMaintenanceUseCase
) {
    /**
     * Tests an AudiobookShelf draft and returns the server's selectable libraries.
     *
     * Duplicate root detection is intentionally deferred to [saveServer], because a single
     * AudiobookShelf server can expose several libraries and the selected library id is not known until
     * after this connection test succeeds.
     */
    suspend fun testConnection(
        baseUrl: String,
        username: String,
        password: String,
        editingRootId: String?
    ): AbsConnectionTestOutcome = withContext(Dispatchers.IO) {
        val token = resolveTokenForConnection(baseUrl, username, password, editingRootId)
        val result = connectionTester.testConnection(baseUrl, token)
        AbsConnectionTestOutcome(
            result = result,
            snapshot = AbsConnectionReuseSnapshot(
                baseUrl = normalizeAbsBaseUrlForReuse(baseUrl),
                username = username.trim(),
                token = token,
                connection = result
            )
        )
    }

    /**
     * Persists a selected AudiobookShelf library after blocking exact duplicate roots.
     *
     * New additions compare normalized server URL plus selected library id, allowing the same server to
     * host multiple roots while preventing the same library from being added twice.
     */
    suspend fun saveServer(
        baseUrl: String,
        username: String,
        password: String,
        libraryId: String,
        libraryName: String,
        editingRootId: String?,
        reuseSnapshot: AbsConnectionReuseSnapshot?
    ): AbsServerSaveOutcome = withContext(Dispatchers.IO) {
        if (editingRootId.isNullOrBlank()) {
            requireUniqueAbsRootForNewConnection(
                roots = libraryRootGateway.getAllRootsOnce(),
                baseUrl = baseUrl,
                libraryId = libraryId,
                editingRootId = editingRootId
            )
        }
        val reusable = reuseSnapshot?.takeIf { snapshot ->
            shouldReuseAbsConnectionSnapshot(
                snapshot = snapshot,
                baseUrl = baseUrl,
                username = username,
                libraryId = libraryId
            )
        }
        val token = reusable?.token ?: resolveTokenForConnection(baseUrl, username, password, editingRootId)
        val connection = reusable?.connection ?: connectionTester.testConnection(baseUrl, token)
        val credentialId = editingRootId
            ?.let { rootId -> libraryRootDao.getRootById(rootId)?.credentialId }
            ?: UUID.randomUUID().toString()
        val credential = credentialStore.save(
            baseUrl = baseUrl,
            token = token,
            userId = connection.userId,
            username = connection.username,
            credentialId = credentialId
        )
        val root = if (editingRootId != null) {
            libraryRootManagementUseCase.updateAbsLibraryRoot(
                id = editingRootId,
                credentialId = credential.id,
                libraryId = libraryId,
                displayName = libraryName
            ).also {
                maintenanceUseCase.clearRootCacheAndRecover(rootId = editingRootId)
            }
        } else {
            libraryRootGateway.addAbsLibraryRoot(
                credentialId = credential.id,
                libraryId = libraryId,
                displayName = libraryName
            )
        }
        AbsServerSaveOutcome(
            rootId = root.id,
            snapshot = AbsConnectionReuseSnapshot(
                baseUrl = normalizeAbsBaseUrlForReuse(baseUrl),
                username = username.trim(),
                token = token,
                connection = connection
            )
        )
    }

    private suspend fun resolveTokenForConnection(
        baseUrl: String,
        username: String,
        password: String,
        editingRootId: String?
    ): String {
        if (password.isBlank() && editingRootId != null) {
            val existingRoot = libraryRootDao.getRootById(editingRootId)
            val credential = existingRoot?.credentialId?.let { credentialId -> credentialStore.get(credentialId) }
            return credential?.token ?: throw IllegalArgumentException("ABS 凭据读取失败且密码为空")
        }
        return requireNotNull(apiClient.login(baseUrl, username, password).user?.token)
    }
}

/**
 * Aligns user input URLs during reuse validation.
 * Aligns user input URLs by validating and normalizing them using the unified AbsUrlResolver.
 */
fun normalizeAbsBaseUrlForReuse(baseUrl: String): String =
    AbsUrlResolver.resolveBaseUrl(baseUrl).toString().trimEnd('/')

/**
 * Determines whether a cached connection snapshot can be safely reused.
 * The cache is rejected whenever server, username, or selected library differs from the last successful connection test.
 */
fun shouldReuseAbsConnectionSnapshot(
    snapshot: AbsConnectionReuseSnapshot,
    baseUrl: String,
    username: String,
    libraryId: String
): Boolean {
    if (normalizeAbsBaseUrlForReuse(baseUrl) != snapshot.baseUrl) return false
    if (username.trim() != snapshot.username) return false
    return snapshot.connection.bookLibraries.any { library -> library.id == libraryId }
}
