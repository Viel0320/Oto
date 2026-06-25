package com.viel.oto.application.usecase

import com.viel.oto.abs.auth.AbsCredential
import com.viel.oto.abs.auth.AbsCredentialStore
import com.viel.oto.data.abs.sync.AbsSyncStateDao
import com.viel.oto.data.dao.BookDao
import com.viel.oto.data.dao.LibraryRootDao
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.root.LibraryRootGateway
import com.viel.oto.data.webdav.WebDavCredential
import com.viel.oto.data.webdav.WebDavCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

data class LibraryRootSettingsSnapshot(
    val rootId: String,
    val sourceType: AudiobookSchema.LibrarySourceType,
    val sourceUri: String,
    val basePath: String,
    val credentialId: String?,
    val displayName: String,
    val status: AudiobookSchema.LibraryRootStatus,
    val availabilityStatus: AudiobookSchema.AvailabilityStatus,
    val lastScannedAt: Long,
    val absLastError: String?,
    val absLastFullSyncAt: Long?,
    val importedBookCount: Int
)

data class WebDavResolvedCredentials(
    val username: String,
    val password: String
)

/**
 * Aggregates settings screen read models and credential lookups.
 * SettingsViewModel now consumes this compact interface instead of reaching into Room DAOs or protocol credential stores directly.
 */
class SettingsQueryUseCase(
    private val libraryRootGateway: LibraryRootGateway,
    private val absSyncStateDao: AbsSyncStateDao,
    private val bookDao: BookDao,
    private val libraryRootDao: LibraryRootDao,
    private val webDavCredentialStore: WebDavCredentialStore,
    private val absCredentialStore: AbsCredentialStore
) {
    fun observeLibraryRootSnapshots(): Flow<List<LibraryRootSettingsSnapshot>> = combine(
        libraryRootGateway.observeLibraryRoots(),
        absSyncStateDao.observeAll(),
        bookDao.getAllBooks()
    ) { roots, syncStates, books ->
        val bookCountsByRootId = books.groupingBy { book -> book.rootId }.eachCount()
        val syncByRootId = syncStates.associateBy { state -> state.rootId }
        roots.map { root ->
            val sync = syncByRootId[root.id]
            LibraryRootSettingsSnapshot(
                rootId = root.id,
                sourceType = root.sourceType,
                sourceUri = root.sourceUri,
                basePath = root.basePath,
                credentialId = root.credentialId,
                displayName = root.displayName,
                status = root.status,
                availabilityStatus = root.availabilityStatus,
                lastScannedAt = root.lastScannedAt,
                absLastError = sync?.lastError,
                absLastFullSyncAt = sync?.lastFullSyncAt,
                importedBookCount = bookCountsByRootId[root.id] ?: 0
            )
        }
    }

    suspend fun getWebDavCredential(credentialId: String?): WebDavCredential? =
        withContext(Dispatchers.IO) {
            credentialId?.takeIf { id -> id.isNotBlank() }?.let { id -> webDavCredentialStore.get(id) }
        }

    suspend fun getAbsCredential(credentialId: String?): AbsCredential? =
        withContext(Dispatchers.IO) {
            credentialId?.takeIf { id -> id.isNotBlank() }?.let { id -> absCredentialStore.get(id) }
        }

    suspend fun resolveWebDavCredentials(
        username: String,
        password: String,
        editingRootId: String?
    ): WebDavResolvedCredentials = withContext(Dispatchers.IO) {
        if (editingRootId.isNullOrBlank() || (username.isNotBlank() && password.isNotBlank())) {
            return@withContext WebDavResolvedCredentials(username = username, password = password)
        }
        val existingCredential = libraryRootDao.getRootById(editingRootId)
            ?.credentialId
            ?.let { credentialId -> webDavCredentialStore.get(credentialId) }
        WebDavResolvedCredentials(
            username = username.ifBlank { existingCredential?.username.orEmpty() },
            password = password.ifBlank { existingCredential?.password.orEmpty() }
        )
    }
}
