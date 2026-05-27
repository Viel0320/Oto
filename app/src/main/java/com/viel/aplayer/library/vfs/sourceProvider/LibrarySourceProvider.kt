package com.viel.aplayer.library.vfs.sourceProvider

import android.content.Context
import android.os.ParcelFileDescriptor
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.vfs.sourceProvider.saf.SafSourceProvider
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavSourceProvider
import java.io.InputStream

enum class LibrarySourceKind(val schemaValue: String) {
    SAF(AudiobookSchema.LibrarySourceType.SAF),
    WEBDAV(AudiobookSchema.LibrarySourceType.WEBDAV);

    companion object {
        fun from(value: String): LibrarySourceKind =
            entries.firstOrNull { it.schemaValue == value } ?: SAF
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
    // offset 转换现在强制下沉到各个 provider 自己实现。
    // 原因是不同来源的最佳实现完全不同：
    // 1. SAF 可以直接走 FileDescriptor/channel.position(offset)
    // 2. WebDAV 应优先发 Range 请求而不是先拿整流再本地 skip
    // 3. 以后新增 provider 时，也必须在自己的协议/存储语义里决定 offset 的边界、错误和性能策略
    suspend fun openInputStream(file: SourceNode, offset: Long): InputStream?
    // `readRange` 也不再由接口层把 offset stream 二次适配成 ByteArray。
    // 这样可以彻底避免公共层替 provider 做 I/O 策略决策：
    // 1. 本地来源可以直接走最省拷贝的随机读取
    // 2. 远程来源可以自己决定是否发送闭区间 Range、如何处理 200/206/416、以及是否拒绝整文件回退
    // 3. 后续新增来源时，编译器会强制它明确声明自己的 range 语义，而不是偷偷吃到接口兜底
    suspend fun readRange(file: SourceNode, offset: Long, length: Int): ByteArray?

    suspend fun openFileDescriptor(file: SourceNode): ParcelFileDescriptor?
    suspend fun exists(node: SourceNode): Boolean
}

class LibrarySourceProviderFactory(private val context: Context) {
    private val safProvider = SafSourceProvider(context)
    private val webDavProvider = WebDavSourceProvider(context.applicationContext)

    fun providerFor(root: LibraryRootEntity): LibrarySourceProvider =
        when (LibrarySourceKind.from(root.sourceType)) {
            LibrarySourceKind.SAF -> safProvider
            LibrarySourceKind.WEBDAV -> webDavProvider
        }
}
