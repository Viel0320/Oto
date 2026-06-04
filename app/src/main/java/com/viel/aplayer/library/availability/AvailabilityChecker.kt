package com.viel.aplayer.library.availability

import android.content.Context
import androidx.core.net.toUri
import com.viel.aplayer.abs.net.AbsApiError
import com.viel.aplayer.abs.vfs.AbsSourceProvider
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.vfs.VfsPath
import com.viel.aplayer.library.vfs.VirtualFileSystem
import com.viel.aplayer.library.vfs.sourceProvider.LibrarySourceKind
import com.viel.aplayer.library.vfs.sourceProvider.LibrarySourceProviderFactory
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavException

// Encapsulate Availability State (Storage Decoupling)
// Uses a dedicated data model instead of plain Booleans to properly distinguish SAF revocations, WebDAV authentication errors, and network issues.
data class AvailabilityResult(
    val status: String,
    val checkedAt: Long = System.currentTimeMillis(),
    val errorCode: String? = null,
    val message: String? = null
) {
    val isAvailable: Boolean get() = status == AudiobookSchema.AvailabilityStatus.AVAILABLE
}

// Verify Remote and Local Storage Availability (Infrastructure Interface)
// Serves as the central component for validating root directories and book files across SAF, WebDAV, and ABS.
class AvailabilityChecker(private val context: Context) {
    private val database = AppDatabase.getInstance(context.applicationContext)
    private val libraryRootDao = database.libraryRootDao()
    private val vfs = VirtualFileSystem(LibrarySourceProviderFactory(context.applicationContext))

    suspend fun checkRoot(root: LibraryRootEntity): AvailabilityResult =
        when (LibrarySourceKind.from(root.sourceType)) {
            LibrarySourceKind.SAF -> checkSafRoot(root)
            // Resolve WebDAV Roots Via VFS (Network Decoupling)
            // Relies on VFS/Provider checking; OkHttp and network errors map to unified availability states.
            LibrarySourceKind.WEBDAV -> checkVfsRoot(root)
            // ABS Root Availability Placeholder (Temporary Interface Binding)
            // Redirect ABS root checks to the virtual file system provider.
            LibrarySourceKind.ABS -> checkVfsRoot(root)
            null -> AvailabilityResult(
                status = AudiobookSchema.AvailabilityStatus.UNSUPPORTED,
                errorCode = "UNSUPPORTED_SOURCE_TYPE"
            )
        }

    suspend fun checkBookFile(file: BookFileEntity): AvailabilityResult =
        runCatching {
            val root = libraryRootDao.getRootById(file.rootId)
                ?: return AvailabilityResult(
                    status = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                    errorCode = AudiobookSchema.AvailabilityStatus.NOT_FOUND
                )
            if (LibrarySourceKind.from(root.sourceType) == LibrarySourceKind.ABS) {
                val provider = LibrarySourceProviderFactory(context.applicationContext).providerFor(root) as AbsSourceProvider
                return@runCatching if (provider.checkReadable(root, file.sourcePath)) {
                    AvailabilityResult(status = AudiobookSchema.AvailabilityStatus.AVAILABLE)
                } else {
                    AvailabilityResult(
                        status = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                        errorCode = AudiobookSchema.AvailabilityStatus.NOT_FOUND
                    )
                }
            }
            // Resolve Files Via VFS Paths (Storage Decoupling)
            // Verify file presence by calling vfs.resolve with sourcePath rather than mapping back to raw SAF content URIs.
            val node = vfs.resolve(root, VfsPath(file.sourcePath))
            if (node != null && vfs.exists(node)) {
                AvailabilityResult(status = AudiobookSchema.AvailabilityStatus.AVAILABLE)
            } else {
                AvailabilityResult(
                    status = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                    errorCode = AudiobookSchema.AvailabilityStatus.NOT_FOUND
                )
            }
        }.getOrElse { throwable -> throwable.toAvailabilityResult() }

    suspend fun checkBookFiles(files: List<BookFileEntity>): Map<String, AvailabilityResult> {
        // Group Checks by Parent Directory (Performance Optimization)
        // Group files by rootId and parent directory to avoid redundant SAF document tree resolutions for multi-file audiobooks.
        if (files.isEmpty()) return emptyMap()
        val results = linkedMapOf<String, AvailabilityResult>()
        files.groupBy { it.rootId }.forEach { (rootId, rootFiles) ->
            val root = libraryRootDao.getRootById(rootId)
            if (root == null) {
                rootFiles.forEach { file -> results[file.id] = notFoundResult() }
            } else {
                when (LibrarySourceKind.from(root.sourceType)) {
                    // Optimize Bulk Checks via VFS (Unified Performance Path)
                    // Performs bulk availability tests by listChildren queries on parent paths, shared by SAF and WebDAV.
                    LibrarySourceKind.SAF,
                    LibrarySourceKind.WEBDAV,
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
        runCatching {
            files.groupBy { it.sourcePath.substringBeforeLast('/', missingDelimiterValue = "") }
                .forEach { (parentPath, siblings) ->
                    val directory = vfs.resolve(root, VfsPath(parentPath))
                    if (directory == null) {
                        siblings.forEach { file -> results[file.id] = notFoundResult() }
                        return@forEach
                    }
                    // Query Child Paths in Directory (Performance Optimization)
                    // Confirms file existence by matching against child paths instead of performing separate, heavy findFile walks for each file.
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
        // Fallback to Individual ABS File Checks (Protocol Limitation Guard)
        // The Audiobookshelf API does not support typical directory enumerations and listChildren() returns empty.
        // We must perform individual checkBookFile runs using ABS-specific HEAD/readable semantics,
        // otherwise the entire audiobook is mistakenly flagged as unavailable.
        files.forEach { file ->
            results[file.id] = checkBookFile(file)
        }
    }

    private suspend fun checkVfsRoot(root: LibraryRootEntity): AvailabilityResult =
        runCatching {
            // Query VFS Root Existence (Network Decoupling)
            // Determines existence using the VFS layer to avoid raw, unmanaged HTTP requests in core logic.
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
                errorCode = AudiobookSchema.AvailabilityStatus.REVOKED
            )
        }
        val exists = vfs.root(root)?.let { vfs.exists(it) } == true
        return if (exists) {
            AvailabilityResult(status = AudiobookSchema.AvailabilityStatus.AVAILABLE)
        } else {
            AvailabilityResult(
                status = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                errorCode = AudiobookSchema.AvailabilityStatus.NOT_FOUND
            )
        }
    }

    private fun notFoundResult(): AvailabilityResult =
        AvailabilityResult(
            status = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
            errorCode = AudiobookSchema.AvailabilityStatus.NOT_FOUND
        )

    private fun Throwable.toAvailabilityResult(): AvailabilityResult {
        val webDavError = this as? WebDavException
        val absError = this as? AbsApiError
        val status = webDavError?.availabilityStatus
            ?: absError?.availabilityStatus
            ?: AudiobookSchema.AvailabilityStatus.UNKNOWN
        // Propagate Mapping Error Codes (Diagnostics Preservation)
        // Passes down the remote errorCode mapped by the provider, defaulting to UNKNOWN only for unhandled exceptions.
        return AvailabilityResult(
            status = status,
            errorCode = webDavError?.availabilityStatus ?: absError?.code ?: this::class.java.simpleName,
            message = localizedMessage ?: message
        )
    }
}
