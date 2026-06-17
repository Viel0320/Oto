package com.viel.aplayer.data.root

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.data.cache.CacheEvictionCoordinator
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.scan.ScanScheduler
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
class LibraryRootGatewayImpl(
    context: Context,
    private val libraryRootDao: LibraryRootDao,
    private val bookDao: BookDao,
    private val scanScheduler: ScanScheduler,
    private val cacheEvictionCoordinator: CacheEvictionCoordinator,
    private val rootStoreOverride: LibraryRootStore? = null,
    private val webDavCredentialStoreOverride: WebDavCredentialStore? = null,
    private val absCredentialStoreOverride: AbsCredentialStore? = null,
    // Database Coordinator Override (Keeps Room transaction tests on the same in-memory database)
    // Production callers omit this parameter and continue to use the process-wide AppDatabase singleton.
    private val databaseOverride: AppDatabase? = null
) : LibraryRootGateway, java.io.Closeable {

    // Application Context Binding (Lifecycle leak prevention)
    // Captures the global context to avoid referencing and leaking short-lived Activity lifecycles.
    private val appContext = context.applicationContext

    // Core Storage Managers (Low-level protocol abstractions)
    // Handles directory registrations and server configuration store managers.
    private val rootStore = rootStoreOverride ?: LibraryRootStore(appContext)
    private val webDavCredentialStore = webDavCredentialStoreOverride ?: WebDavCredentialStore(appContext)
    private val absCredentialStore = absCredentialStoreOverride ?: AbsCredentialStore.getInstance(appContext)
    private val database by lazy {
        // Database Instance Selection (Align injected DAOs with transaction ownership)
        // Tests can supply the exact Room instance backing the DAOs so rollback assertions exercise real SQLite behavior.
        databaseOverride ?: AppDatabase.getInstance(appContext)
    }

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

        val sourceKind = LibrarySourceKind.from(root.sourceType)
        val postCommitCleanup = database.withTransaction {
            // Root Delete Transaction (Commit Room-owned cleanup and root deletion together)
            // ABS mirror, sync, session, pending progress, and root rows must succeed or roll back as one SQLite unit.
            val cleanup = buildPostCommitRootCleanup(root = root, sourceKind = sourceKind)
            deleteRoomOwnedRootData(root = root, sourceKind = sourceKind)
            // Room Root Deletion (Cascade root-owned book/file rows after manual ABS rows are staged for deletion)
            // The final root delete is inside the same transaction so failures cannot leave partial ABS cleanup committed.
            libraryRootDao.deleteRoot(root)
            cleanup
        }
        runPostCommitRootCleanup(root = root, cleanup = postCommitCleanup)
    }

    private suspend fun buildPostCommitRootCleanup(
        root: LibraryRootEntity,
        sourceKind: LibrarySourceKind?
    ): PostCommitRootCleanup {
        return when (sourceKind) {
            LibrarySourceKind.SAF -> PostCommitRootCleanup.ReleaseSafPermission(root.sourceUri)
            LibrarySourceKind.WEBDAV -> PostCommitRootCleanup.DeleteWebDavCredential(root.credentialId)
            LibrarySourceKind.ABS -> {
                // ABS Credential Reference Check (Multi-root secret isolation)
                // The credential is selected inside the Room transaction but deleted only after the root delete commits.
                if (shouldDeleteAbsCredential(root, libraryRootDao.getAllRootsOnce())) {
                    PostCommitRootCleanup.DeleteAbsCredential(root.credentialId)
                } else {
                    PostCommitRootCleanup.None
                }
            }
            null -> {
                ScanWorkflowLogger.warn("libraryRootService unknown sourceType during delete: sourceType=${root.sourceType}")
                PostCommitRootCleanup.None
            }
        }
    }

    private suspend fun deleteRoomOwnedRootData(
        root: LibraryRootEntity,
        sourceKind: LibrarySourceKind?
    ) {
        if (sourceKind != LibrarySourceKind.ABS) return
        val bookIds = bookDao.getBooksByRootId(root.id).map { book -> book.id }
        // ABS Manual Room Cleanup (Remove tables that do not yet have SQLite cascade ownership)
        // These deletes run before the root row delete but stay inside the same transaction, so root delete failures roll them back.
        database.absSyncStateDao().deleteByRootId(root.id)
        database.absItemMirrorDao().deleteByRootId(root.id)
        if (bookIds.isNotEmpty()) {
            database.absPlaybackSessionDao().deleteByBookIds(bookIds)
            database.absPendingProgressSyncDao().deleteByBookIds(bookIds)
        }
    }

    private suspend fun runPostCommitRootCleanup(
        root: LibraryRootEntity,
        cleanup: PostCommitRootCleanup
    ) {
        when (cleanup) {
            is PostCommitRootCleanup.ReleaseSafPermission -> {
                try {
                    val uri = cleanup.sourceUri.toUri()
                    appContext.contentResolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    ScanWorkflowLogger.error("libraryRootService release SAF permission failed: rootId=${root.id}", e)
                }
            }
            is PostCommitRootCleanup.DeleteWebDavCredential -> {
                webDavCredentialStore.delete(cleanup.credentialId)
            }
            is PostCommitRootCleanup.DeleteAbsCredential -> {
                absCredentialStore.delete(cleanup.credentialId)
            }
            PostCommitRootCleanup.None -> Unit
        }
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
        val oldRoot = libraryRootDao.getRootById(id)
        val updated = rootStore.updateAbsRoot(id, credentialId, libraryId, displayName)
        if (oldRoot != null && oldRoot.basePath != libraryId) {
            /**
             * Purge Cache and Clear Fingerprint on Library Switch (Wipes old catalog records and sync state to force a clean reload)
             * Truncates local mirror mappings, database books, and schedules a full list pull for the newly linked library.
             */
            database.withTransaction {
                val bookIds = bookDao.getBooksByRootId(id).map { book -> book.id }
                bookDao.deleteBooksByRootId(id)
                database.absSyncStateDao().deleteByRootId(id)
                database.absItemMirrorDao().deleteByRootId(id)
                if (bookIds.isNotEmpty()) {
                    database.absPlaybackSessionDao().deleteByBookIds(bookIds)
                    database.absPendingProgressSyncDao().deleteByBookIds(bookIds)
                }
            }
        }
        updated
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

private sealed class PostCommitRootCleanup {
    // Post-Commit SAF Cleanup (Releases platform URI grants only after SQLite no longer has a live root)
    // This prevents database failures from leaving an active SAF root without its persisted permission.
    data class ReleaseSafPermission(val sourceUri: String) : PostCommitRootCleanup()

    // Post-Commit WebDAV Cleanup (Removes external credentials after the root row has been deleted)
    // WebDAV secrets live outside Room, so they must not be erased until the database commit succeeds.
    data class DeleteWebDavCredential(val credentialId: String?) : PostCommitRootCleanup()

    // Post-Commit ABS Cleanup (Removes an unshared ABS credential after the root row has been deleted)
    // Shared credentials remain available for sibling ABS roots that still reference the same credential ID.
    data class DeleteAbsCredential(val credentialId: String?) : PostCommitRootCleanup()

    // No External Cleanup (Represents unknown or shared-credential roots with no post-commit side effect)
    // Keeping this explicit avoids encoding no-op cleanup as nullable control flow.
    data object None : PostCommitRootCleanup()
}
