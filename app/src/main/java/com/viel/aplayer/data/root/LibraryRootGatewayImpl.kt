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
 * Implements LibraryRootGateway.
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
    private val databaseOverride: AppDatabase? = null
) : LibraryRootGateway, java.io.Closeable {

    private val appContext = context.applicationContext

    private val rootStore = rootStoreOverride ?: LibraryRootStore(appContext)
    private val webDavCredentialStore = webDavCredentialStoreOverride ?: WebDavCredentialStore(appContext)
    private val absCredentialStore = absCredentialStoreOverride ?: AbsCredentialStore.getInstance(appContext)
    private val database by lazy {
        databaseOverride ?: AppDatabase.getInstance(appContext)
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        ScanWorkflowLogger.error("libraryRootService coroutine failure", exception)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    @Volatile
    private var cachedRoots: List<LibraryRootEntity> = emptyList()

    init {
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
        libraryRootDao.getAllRootsOnce()
    }

    override suspend fun setLibraryRoot(uri: Uri): LibraryRootEntity = withContext(Dispatchers.IO) {
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
        rootStore.addAbsRoot(
            credentialId = credentialId,
            libraryId = libraryId,
            displayName = displayName
        )
    }

    override fun addLibraryRootAndScheduleSync(uri: Uri, trigger: String) {
        scope.launch {
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                setLibraryRoot(uri)
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
                scanScheduler.syncLibrary(trigger)
            }.onFailure { error ->
                ScanWorkflowLogger.error("libraryRootService add WebDAV root failed", error)
            }
        }
    }

    override suspend fun refreshLibraryRootStatuses() = withContext(Dispatchers.IO) {
        rootStore.refreshPermissionStatuses()
        Unit
    }

    override suspend fun refreshLibraryRootStatus(rootId: String) = withContext(Dispatchers.IO) {
        rootStore.refreshRootStatus(rootId)
    }

    override suspend fun deleteLibraryRootDataOnly(root: LibraryRootEntity): Unit = withContext(Dispatchers.IO) {
        try {
            cacheEvictionCoordinator.evictBeforeRootDelete(root)
        } catch (e: Exception) {
            ScanWorkflowLogger.error("libraryRootService cache eviction failed: rootId=${root.id}", e)
        }

        val sourceKind = LibrarySourceKind.from(root.sourceType)
        val postCommitCleanup = database.withTransaction {
            val cleanup = buildPostCommitRootCleanup(root = root, sourceKind = sourceKind)
            deleteRoomOwnedRootData(root = root, sourceKind = sourceKind)
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
             * Wipes old catalog records and sync state to force a clean reload.
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
    data class ReleaseSafPermission(val sourceUri: String) : PostCommitRootCleanup()

    data class DeleteWebDavCredential(val credentialId: String?) : PostCommitRootCleanup()

    data class DeleteAbsCredential(val credentialId: String?) : PostCommitRootCleanup()

    data object None : PostCommitRootCleanup()
}
