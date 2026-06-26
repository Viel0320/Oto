package com.viel.oto.library.availability

import android.content.Context
import androidx.core.net.toUri
import com.viel.oto.data.db.AppDatabase
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.data.runCatchingCancellable
import com.viel.oto.library.vfs.VfsPath
import com.viel.oto.library.vfs.VirtualFileSystem
import com.viel.oto.library.vfs.sourceProvider.LibrarySourceKind
import com.viel.oto.library.vfs.sourceProvider.LibrarySourceProviderFactory
import com.viel.oto.library.vfs.sourceProvider.webdav.WebDavException

data class AvailabilityResult(
    val status: AudiobookSchema.AvailabilityStatus,
    val checkedAt: Long = System.currentTimeMillis(),
    val errorCode: String? = null,
    val message: String? = null
) {
    val isAvailable: Boolean get() = status == AudiobookSchema.AvailabilityStatus.AVAILABLE
}

class AvailabilityChecker(
    private val context: Context,
    private val database: AppDatabase,
    providerFactory: LibrarySourceProviderFactory = LibrarySourceProviderFactory(context.applicationContext),
    private val absAvailabilityGateway: AbsAvailabilityGateway? = null
) {
    private val libraryRootDao = database.libraryRootDao()
    private val vfs = VirtualFileSystem(providerFactory)

    suspend fun checkRoot(root: LibraryRootEntity): AvailabilityResult =
        when (LibrarySourceKind.from(root.sourceType)) {
            LibrarySourceKind.SAF -> checkSafRoot(root)
            LibrarySourceKind.WEBDAV -> checkVfsRoot(root)
            LibrarySourceKind.ABS -> checkAbsRoot(root)
            null -> AvailabilityResult(
                status = AudiobookSchema.AvailabilityStatus.UNSUPPORTED,
                errorCode = "UNSUPPORTED_SOURCE_TYPE"
            )
        }

    suspend fun checkBookFile(file: BookFileEntity): AvailabilityResult =
        runCatchingCancellable {
            val root = libraryRootDao.getRootById(file.rootId)
                ?: return AvailabilityResult(
                    status = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                    errorCode = AudiobookSchema.AvailabilityStatus.NOT_FOUND.name
                )
            if (LibrarySourceKind.from(root.sourceType) == LibrarySourceKind.ABS) {
                return@runCatchingCancellable requireAbsAvailabilityGateway().checkBookFile(root, file)
            }
            val node = vfs.resolve(root, VfsPath(file.sourcePath))
            if (node != null && vfs.exists(node)) {
                AvailabilityResult(status = AudiobookSchema.AvailabilityStatus.AVAILABLE)
            } else {
                AvailabilityResult(
                    status = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                    errorCode = AudiobookSchema.AvailabilityStatus.NOT_FOUND.name
                )
            }
        }.getOrElse { throwable -> throwable.toAvailabilityResult() }

    suspend fun checkBookFiles(files: List<BookFileEntity>): Map<String, AvailabilityResult> {
        if (files.isEmpty()) return emptyMap()
        val results = linkedMapOf<String, AvailabilityResult>()
        files.groupBy { it.rootId }.forEach { (rootId, rootFiles) ->
            val root = libraryRootDao.getRootById(rootId)
            if (root == null) {
                rootFiles.forEach { file -> results[file.id] = notFoundResult() }
            } else {
                when (LibrarySourceKind.from(root.sourceType)) {
                    LibrarySourceKind.SAF,
                    LibrarySourceKind.WEBDAV -> checkVfsBookFiles(root, rootFiles, results)
                    LibrarySourceKind.ABS -> checkAbsBookFiles(rootFiles, results)
                    null -> rootFiles.forEach { file ->
                        results[file.id] = AvailabilityResult(
                            status = AudiobookSchema.AvailabilityStatus.UNSUPPORTED,
                            errorCode = "UNSUPPORTED_SOURCE_TYPE"
                        )
                    }
                }
            }
        }
        return results
    }

    private suspend fun checkVfsBookFiles(
        root: LibraryRootEntity,
        files: List<BookFileEntity>,
        results: MutableMap<String, AvailabilityResult>
    ) {
        runCatchingCancellable {
            files.groupBy { it.sourcePath.substringBeforeLast('/', missingDelimiterValue = "") }
                .forEach { (parentPath, siblings) ->
                    val directory = vfs.resolve(root, VfsPath(parentPath))
                    if (directory == null) {
                        siblings.forEach { file -> results[file.id] = notFoundResult() }
                        return@forEach
                    }
                    val existingChildPaths = vfs.listChildren(directory)
                        .asSequence()
                        .filterNot { it.metadata.isDirectory }
                        .map { it.metadata.sourcePath }
                        .toSet()
                    siblings.forEach { file ->
                        results[file.id] = if (existingChildPaths.contains(file.sourcePath)) {
                            AvailabilityResult(status = AudiobookSchema.AvailabilityStatus.AVAILABLE)
                        } else {
                            notFoundResult()
                        }
                    }
                }
        }.getOrElse { throwable ->
            val failure = throwable.toAvailabilityResult()
            files.forEach { file -> results[file.id] = failure }
        }
    }

    private suspend fun checkAbsBookFiles(
        files: List<BookFileEntity>,
        results: MutableMap<String, AvailabilityResult>
    ) {
        files.forEach { file ->
            results[file.id] = checkBookFile(file)
        }
    }

    private suspend fun checkVfsRoot(root: LibraryRootEntity): AvailabilityResult =
        runCatchingCancellable {
            val exists = vfs.root(root)?.let { vfs.exists(it) } == true
            if (exists) {
                AvailabilityResult(status = AudiobookSchema.AvailabilityStatus.AVAILABLE)
            } else {
                notFoundResult()
            }
        }.getOrElse { throwable -> throwable.toAvailabilityResult() }

    private suspend fun checkSafRoot(root: LibraryRootEntity): AvailabilityResult {
        val sourceUri = root.sourceUri.toUri()
        val hasPersistedReadGrant = context.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && permission.uri.normalizeScheme().toString() == sourceUri.normalizeScheme().toString()
        }
        if (!hasPersistedReadGrant) {
            return AvailabilityResult(
                status = AudiobookSchema.AvailabilityStatus.REVOKED,
                errorCode = AudiobookSchema.AvailabilityStatus.REVOKED.name
            )
        }
        val exists = vfs.root(root)?.let { vfs.exists(it) } == true
        return if (exists) {
            AvailabilityResult(status = AudiobookSchema.AvailabilityStatus.AVAILABLE)
        } else {
            AvailabilityResult(
                status = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                errorCode = AudiobookSchema.AvailabilityStatus.NOT_FOUND.name
            )
        }
    }

    /**
     * Validates server credentials and selected library membership.
     * Confirms the saved token still authorizes successfully and that the configured book library ID is still returned by the server.
     */
    private suspend fun checkAbsRoot(root: LibraryRootEntity): AvailabilityResult =
        requireAbsAvailabilityGateway().checkRoot(root)

    private fun notFoundResult(): AvailabilityResult =
        AvailabilityResult(
            status = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
            errorCode = AudiobookSchema.AvailabilityStatus.NOT_FOUND.name
        )

    private fun Throwable.toAvailabilityResult(): AvailabilityResult {
        val webDavError = this as? WebDavException
        return AvailabilityResult(
            status = webDavError?.availabilityStatus ?: AudiobookSchema.AvailabilityStatus.UNKNOWN,
            errorCode = webDavError?.availabilityStatus?.name ?: this::class.java.simpleName,
            message = localizedMessage ?: message
        )
    }

    /**
     * Returns the ABS availability adapter only for ABS source checks.
     */
    private fun requireAbsAvailabilityGateway(): AbsAvailabilityGateway =
        requireNotNull(absAvailabilityGateway) { "AvailabilityChecker requires AbsAvailabilityGateway for ABS roots." }
}
