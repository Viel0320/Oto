package com.viel.oto.application.usecase

import com.viel.oto.abs.auth.AbsCredential
import com.viel.oto.abs.auth.AbsCredentialStore
import com.viel.oto.data.abs.sync.AbsSyncStateDao
import com.viel.oto.data.dao.BookDao
import com.viel.oto.data.dao.LibraryRootDao
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.library.root.LibraryRootGateway
import com.viel.oto.data.webdav.WebDavCredential
import com.viel.oto.data.webdav.WebDavCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/**
 * Source kind exposed to settings presentation without leaking persisted database constants to UI.
 */
enum class SettingsRootSourceKind {
    SAF,
    WEB_DAV,
    ABS
}

/**
 * Root lifecycle status exposed to settings presentation without requiring UI to import schema constants.
 */
enum class SettingsRootStatusKind {
    ACTIVE,
    REVOKED,
    ERROR
}

/**
 * Root availability state exposed to settings presentation without requiring UI to import schema constants.
 */
enum class SettingsRootAvailabilityKind {
    AVAILABLE,
    REVOKED,
    AUTH_FAILED,
    NETWORK_UNAVAILABLE,
    NOT_FOUND,
    PERMISSION_DENIED,
    SERVER_ERROR,
    TIMEOUT,
    UNSUPPORTED,
    UNKNOWN
}

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
) {
    val sourceKind: SettingsRootSourceKind
        get() = when (sourceType) {
            AudiobookSchema.LibrarySourceType.SAF -> SettingsRootSourceKind.SAF
            AudiobookSchema.LibrarySourceType.WEBDAV -> SettingsRootSourceKind.WEB_DAV
            AudiobookSchema.LibrarySourceType.ABS -> SettingsRootSourceKind.ABS
        }

    val statusKind: SettingsRootStatusKind
        get() = when (status) {
            AudiobookSchema.LibraryRootStatus.ACTIVE -> SettingsRootStatusKind.ACTIVE
            AudiobookSchema.LibraryRootStatus.REVOKED -> SettingsRootStatusKind.REVOKED
            AudiobookSchema.LibraryRootStatus.ERROR -> SettingsRootStatusKind.ERROR
        }

    val availabilityKind: SettingsRootAvailabilityKind
        get() = when (availabilityStatus) {
            AudiobookSchema.AvailabilityStatus.AVAILABLE -> SettingsRootAvailabilityKind.AVAILABLE
            AudiobookSchema.AvailabilityStatus.REVOKED -> SettingsRootAvailabilityKind.REVOKED
            AudiobookSchema.AvailabilityStatus.AUTH_FAILED -> SettingsRootAvailabilityKind.AUTH_FAILED
            AudiobookSchema.AvailabilityStatus.NETWORK_UNAVAILABLE -> SettingsRootAvailabilityKind.NETWORK_UNAVAILABLE
            AudiobookSchema.AvailabilityStatus.NOT_FOUND -> SettingsRootAvailabilityKind.NOT_FOUND
            AudiobookSchema.AvailabilityStatus.PERMISSION_DENIED -> SettingsRootAvailabilityKind.PERMISSION_DENIED
            AudiobookSchema.AvailabilityStatus.SERVER_ERROR -> SettingsRootAvailabilityKind.SERVER_ERROR
            AudiobookSchema.AvailabilityStatus.TIMEOUT -> SettingsRootAvailabilityKind.TIMEOUT
            AudiobookSchema.AvailabilityStatus.UNSUPPORTED -> SettingsRootAvailabilityKind.UNSUPPORTED
            AudiobookSchema.AvailabilityStatus.UNKNOWN -> SettingsRootAvailabilityKind.UNKNOWN
        }

    val isAbsRoot: Boolean
        get() = sourceKind == SettingsRootSourceKind.ABS

    val isWebDavRoot: Boolean
        get() = sourceKind == SettingsRootSourceKind.WEB_DAV

    val isActive: Boolean
        get() = statusKind == SettingsRootStatusKind.ACTIVE

    val hasKnownAvailability: Boolean
        get() = availabilityKind != SettingsRootAvailabilityKind.UNKNOWN

    val isAvailable: Boolean
        get() = availabilityKind == SettingsRootAvailabilityKind.AVAILABLE
}

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
