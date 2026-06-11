package com.viel.aplayer.library.vfs.sourceProvider

import android.content.Context
import android.os.ParcelFileDescriptor
import com.viel.aplayer.abs.vfs.AbsSourceProvider
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.vfs.sourceProvider.saf.SafSourceProvider
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavSourceProvider
import java.io.InputStream

enum class LibrarySourceKind(val schemaValue: AudiobookSchema.LibrarySourceType) {
    SAF(AudiobookSchema.LibrarySourceType.SAF),
    WEBDAV(AudiobookSchema.LibrarySourceType.WEBDAV),
    ABS(AudiobookSchema.LibrarySourceType.ABS);

    companion object {
        // Source Type Safe Mapping: Map from AudiobookSchema.LibrarySourceType enum class.
        fun from(value: AudiobookSchema.LibrarySourceType): LibrarySourceKind? =
            entries.firstOrNull { it.schemaValue == value }
    }
}

data class SourceCapabilities(
    val supportsDirectoryListing: Boolean = true,
    val supportsLastModified: Boolean = true,
    val supportsEtag: Boolean = false,
    val supportsRangeRead: Boolean = false
)

data class SourceFileMetadata(
    val sourcePath: String,
    val identity: String,
    val parentSourcePath: String,
    val parentIdentity: String,
    val displayName: String,
    val isDirectory: Boolean,
    val fileSize: Long,
    val lastModified: Long,
    val etag: String? = null,
    val mimeType: String? = null
)

data class SourceNode(
    val root: LibraryRootEntity,
    val metadata: SourceFileMetadata,
    val providerHandle: Any? = null
)

interface LibrarySourceProvider {
    val kind: LibrarySourceKind
    val capabilities: SourceCapabilities

    suspend fun rootDirectory(root: LibraryRootEntity): SourceNode?
    suspend fun resolve(root: LibraryRootEntity, sourcePath: String): SourceNode?
    suspend fun listChildren(directory: SourceNode): List<SourceNode>
    suspend fun openInputStream(file: SourceNode): InputStream?
    // Offset logic is delegated to individual providers.
    // Different source providers require distinct seeking optimizations:
    // 1. SAF can leverage native FileDescriptor and channel positioning.
    // 2. WebDAV benefits from HTTP Range requests to avoid local client-side skips.
    // 3. Future providers must formulate their own offset boundaries and performance strategies.
    suspend fun openInputStream(file: SourceNode, offset: Long): InputStream?
    // Interface design does not adapt offset stream to ByteArray automatically.
    // Prevents VFS layers from making centralized I/O policy assumptions:
    // 1. Local sources can perform zero-copy random-access reads directly.
    // 2. Remote sources manage HTTP Range headers and handle 200/206/416 codes.
    // 3. Future providers are forced to explicitly implement range reading mechanics.
    suspend fun readRange(file: SourceNode, offset: Long, length: Int): ByteArray?

    suspend fun openFileDescriptor(file: SourceNode): ParcelFileDescriptor?
    suspend fun exists(node: SourceNode): Boolean
}

class LibrarySourceProviderFactory(private val context: Context) {
    private val safProvider = SafSourceProvider(context)
    private val webDavProvider = WebDavSourceProvider(context.applicationContext)
    // Registers ABS provider placeholder in Phase 1 to prevent ABS routes from falling back to SAF or WebDAV.
    private val absProvider = AbsSourceProvider(context.applicationContext)

    fun providerFor(root: LibraryRootEntity): LibrarySourceProvider =
        when (LibrarySourceKind.from(root.sourceType)) {
            LibrarySourceKind.SAF -> safProvider
            LibrarySourceKind.WEBDAV -> webDavProvider
            LibrarySourceKind.ABS -> absProvider
            null -> throw UnsupportedOperationException("Unsupported library source type: ${root.sourceType}")
        }
}
