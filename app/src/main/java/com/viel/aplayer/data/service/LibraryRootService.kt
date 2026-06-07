package com.viel.aplayer.data.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.data.cache.CacheEvictionCoordinator
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.gateway.LibraryRootGateway
import com.viel.aplayer.data.gateway.ScanScheduler
import com.viel.aplayer.library.LibraryRootStore
import com.viel.aplayer.library.vfs.sourceProvider.LibrarySourceKind
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavCredentialStore
import com.viel.aplayer.logger.ScanWorkflowLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Library Roots Management Application Service (Implements LibraryRootGateway)
 * 
 * Core Design Goals:
 * 1. Eradicate God-Class Dependencies: Directly queries narrow schemas by injecting Context, LibraryRootDao, BookDao, and ScanScheduler in the M6d phase.
 * 2. Re-anchor Memory Cache and Purge Steps: Retains takePersistableUriPermission permissions, synchronous cachedRoots syncs, and cover purges upon deletion.
 */
class LibraryRootService(
    context: Context,
    private val libraryRootDao: LibraryRootDao,
    private val bookDao: BookDao,
    private val scanScheduler: ScanScheduler,
    private val cacheEvictionCoordinator: CacheEvictionCoordinator,
    private val rootStoreOverride: LibraryRootStore? = null,
    private val webDavCredentialStoreOverride: WebDavCredentialStore? = null,
    private val absCredentialStoreOverride: AbsCredentialStore? = null
) : LibraryRootGateway, java.io.Closeable {

    // Application Context Binding (Lifecycle leak prevention)
    // Captures the global context to avoid referencing and leaking short-lived Activity lifecycles.
    private val appContext = context.applicationContext

    // Core Storage Managers (Low-level protocol abstractions)
    // Handles directory registrations and server configuration store managers.
    private val rootStore = rootStoreOverride ?: LibraryRootStore(appContext)
    private val webDavCredentialStore = webDavCredentialStoreOverride ?: WebDavCredentialStore(appContext)
    private val absCredentialStore = absCredentialStoreOverride ?: AbsCredentialStore.getInstance(appContext)
    private val database by lazy { com.viel.aplayer.data.db.AppDatabase.getInstance(appContext) }

    // Private Exception Handler (Background thread failure barrier)
    // Intercepts background failures during reactive root cache synchronization.
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        ScanWorkflowLogger.error("libraryRootService coroutine failure", exception)
    }

    // Operations Coroutine Scope (Isolated task execution pool)
    // Dedicated scope bound to Dispatchers.IO for offloading write and cache tracking operations.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    // Hot Cache Cache-Memory (Render delay prevention)
    // Stores memory caches synchronously to avoid first-frame flickering from asynchronous database loads.
    @Volatile
    private var cachedRoots: List<LibraryRootEntity> = emptyList()

    init {
        // Initialize In-Memory Cache (Reactive collection sync)
        // Collects database updates reactively to keep the local cachedRoots snapshot fresh.
        scope.launch {
            observeLibraryRoots().collect {
                cachedRoots = it
            }
        }
    }

    override fun observeLibraryRoots(): Flow<List<LibraryRootEntity>> {
        return libraryRootDao.getAllRoots()
    }

    override fun getCachedLibraryRoots(): List<LibraryRootEntity> {
        return cachedRoots
    }

    override suspend fun getAllRootsOnce(): List<LibraryRootEntity> = withContext(Dispatchers.IO) {
        // Persistent Root Snapshot (Bypasses reactive cache during startup coordination)
        // Cold-start services need a deterministic list even before the cache collector has emitted its first database snapshot.
        libraryRootDao.getAllRootsOnce()
    }

    override suspend fun setLibraryRoot(uri: Uri): LibraryRootEntity = withContext(Dispatchers.IO) {
        // SAF Display Name Delegation (Let the root store derive the selected folder name)
        // Passing a blank label prevents every newly picked SAF library from being stored as the generic "My Library" placeholder.
        rootStore.addRoot(uri, "")
    }

    override suspend fun addWebDavLibraryRoot(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String
    ): LibraryRootEntity = withContext(Dispatchers.IO) {
        rootStore.addWebDavRoot(
            url = url,
            username = username,
            password = password,
            displayName = displayName,
            basePath = basePath
        )
    }

    override suspend fun addAbsLibraryRoot(
        credentialId: String,
        libraryId: String,
        displayName: String
    ): LibraryRootEntity = withContext(Dispatchers.IO) {
        // ABS Registration Isolation (Independent scan scheduling)
        // Writes only the root schema references without scanning files; remote catalog mirroring runs in a separate stage.
        rootStore.addAbsRoot(
            credentialId = credentialId,
            libraryId = libraryId,
            displayName = displayName
        )
    }

    override fun addLibraryRootAndScheduleSync(uri: Uri, trigger: String) {
        scope.launch {
            runCatching {
                // Persistent Permission Acquisition (SAF reachability guard)
                // Requests persistable directory read permissions to prevent access loss upon device reboot.
                appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                setLibraryRoot(uri)
                // Trigger Immediate Rescan (Pipeline synchronization trigger)
                // Dispatches an immediate background sync scan for the newly registered storage root.
                scanScheduler.syncLibrary(trigger)
            }.onFailure { error ->
                ScanWorkflowLogger.error("libraryRootService add SAF root failed", error)
            }
        }
    }

    override fun addWebDavLibraryRootAndScheduleSync(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String,
        trigger: String
    ) {
        scope.launch {
            runCatching {
                addWebDavLibraryRoot(url, username, password, displayName, basePath)
                // Trigger Immediate Rescan (Pipeline synchronization trigger)
                // Dispatches an immediate background sync scan for the newly registered WebDAV root.
                scanScheduler.syncLibrary(trigger)
            }.onFailure { error ->
                ScanWorkflowLogger.error("libraryRootService add WebDAV root failed", error)
            }
        }
    }

    override suspend fun refreshLibraryRootStatuses() = withContext(Dispatchers.IO) {
        // Bulk Root Status Refresh (Keeps gateway command semantics unchanged)
        // Discards detailed snapshots here because settings screens observe persisted root rows rather than consuming direct return payloads.
        rootStore.refreshPermissionStatuses()
        Unit
    }

    override suspend fun refreshLibraryRootStatus(rootId: String) = withContext(Dispatchers.IO) {
        // Targeted Root Status Refresh (Supports root-scoped sync preflight checks)
        // Updates the selected root before manual ABS synchronization or other focused tasks decide whether execution may proceed.
        rootStore.refreshRootStatus(rootId)
    }

    override suspend fun deleteLibraryRootDataOnly(root: LibraryRootEntity): Unit = withContext(Dispatchers.IO) {
        // Pre-Delete Cache Eviction (Clears root-owned cache artifacts while source rows still exist)
        // Runs before root deletion so cover paths and directory cache rows can be collected without moving playback or sync logic into this service.
        try {
            cacheEvictionCoordinator.evictBeforeRootDelete(root)
        } catch (e: Exception) {
            ScanWorkflowLogger.error("libraryRootService cache eviction failed: rootId=${root.id}", e)
        }

        // 2. Revoke permissions or remove secrets based on the root type (SAF tree release, or credential purge).
        when (LibrarySourceKind.from(root.sourceType)) {
            LibrarySourceKind.SAF -> {
                try {
                    val uri = root.sourceUri.toUri()
                    appContext.contentResolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    ScanWorkflowLogger.error("libraryRootService release SAF permission failed: rootId=${root.id}", e)
                }
            }
            LibrarySourceKind.WEBDAV -> {
                webDavCredentialStore.delete(root.credentialId)
            }
            LibrarySourceKind.ABS -> {
                // ABS Credential Reference Check (Multi-root secret isolation)
                // Only removes ABS credentials if no other registered ABS roots share the same credential ID.
                if (shouldDeleteAbsCredential(root, libraryRootDao.getAllRootsOnce())) {
                    absCredentialStore.delete(root.credentialId)
                }
                val bookIds = bookDao.getBooksByRootId(root.id).map { book -> book.id }
                database.absSyncStateDao().deleteByRootId(root.id)
                database.absItemMirrorDao().deleteByRootId(root.id)
                if (bookIds.isNotEmpty()) {
                    database.absPlaybackSessionDao().deleteByBookIds(bookIds)
                    database.absPendingProgressSyncDao().deleteByBookIds(bookIds)
                }
            }
            null -> {
                ScanWorkflowLogger.warn("libraryRootService unknown sourceType during delete: sourceType=${root.sourceType}")
            }
        }

        // 3. Room root deletion: Removes the database row, triggering SQLite CASCADE deletes for child files and books.
        libraryRootDao.deleteRoot(root)
    }

    override fun close() {
        // Cancel Background Scopes (Memory leak cleanup)
        // Cancels the private coroutine scope to close active Flow collection loops upon service teardown.
        scope.cancel()
    }

    override suspend fun updateSafLibraryRoot(id: String, newUri: Uri): LibraryRootEntity = withContext(Dispatchers.IO) {
        rootStore.updateSafRoot(id, newUri)
    }

    override suspend fun updateWebDavLibraryRoot(
        id: String,
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String
    ): LibraryRootEntity = withContext(Dispatchers.IO) {
        rootStore.updateWebDavRoot(id, url, username, password, displayName, basePath)
    }

    override suspend fun updateAbsLibraryRoot(
        id: String,
        credentialId: String,
        libraryId: String,
        displayName: String
    ): LibraryRootEntity = withContext(Dispatchers.IO) {
        rootStore.updateAbsRoot(id, credentialId, libraryId, displayName)
    }
}

internal fun shouldDeleteAbsCredential(
    root: LibraryRootEntity,
    allRoots: List<LibraryRootEntity>
): Boolean {
    if (root.sourceType != com.viel.aplayer.data.db.AudiobookSchema.LibrarySourceType.ABS) return false
    return allRoots.none { otherRoot ->
        otherRoot.id != root.id &&
            otherRoot.sourceType == com.viel.aplayer.data.db.AudiobookSchema.LibrarySourceType.ABS &&
            otherRoot.credentialId == root.credentialId
    }
}
