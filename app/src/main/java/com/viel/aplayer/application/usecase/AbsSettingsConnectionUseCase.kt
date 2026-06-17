package com.viel.aplayer.application.usecase

import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.abs.net.AbsApiClient
import com.viel.aplayer.abs.net.AbsUrlResolver
import com.viel.aplayer.abs.sync.AbsConnectionTestResult
import com.viel.aplayer.abs.sync.AbsConnectionTester
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.root.LibraryRootGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * ABS connection reuse data snapshot (Caches successful testing credentials)
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
 * ABS Settings Connection Use Case (Owns login, token reuse, credential persistence, and root registration)
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

    suspend fun saveServer(
        baseUrl: String,
        username: String,
        password: String,
        libraryId: String,
        libraryName: String,
        editingRootId: String?,
        reuseSnapshot: AbsConnectionReuseSnapshot?
    ): AbsServerSaveOutcome = withContext(Dispatchers.IO) {
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
            // ABS Root Management Use (Route library switches through cleanup-first root management)
            // ABS login and credential reuse stay here, while old mirrored covers and manual downloads are cleared before cascade deletion.
            libraryRootManagementUseCase.updateAbsLibraryRoot(
                id = editingRootId,
                credentialId = credential.id,
                libraryId = libraryId,
                displayName = libraryName
            ).also {
                maintenanceUseCase.clearRootCacheAndRecover(rootId = editingRootId)
            }
        } else {
            // ABS Root Creation Gateway Use (Register new remote roots through the narrow root seam)
            // This avoids taking the broad UI-facing facade into ABS settings orchestration.
            libraryRootGateway.addAbsLibraryRoot(
                credentialId = credential.id,
                libraryId = libraryId,
                displayName = libraryName
            )
        }
        AbsServerSaveOutcome(
            // ABS Save Result Projection (Return only the stable command anchor to presentation)
            // SettingsViewModel needs the saved root id for logging and auto-sync scheduling, not the Room entity that was persisted by the gateway.
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
 * Normalize baseUrl value (Aligns user input URLs during reuse validation)
 * Aligns user input URLs by validating and normalizing them using the unified AbsUrlResolver.
 */
fun normalizeAbsBaseUrlForReuse(baseUrl: String): String =
    AbsUrlResolver.resolveBaseUrl(baseUrl).toString().trimEnd('/')

/**
 * Validate ABS reuse criteria (Determines whether a cached connection snapshot can be safely reused)
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
