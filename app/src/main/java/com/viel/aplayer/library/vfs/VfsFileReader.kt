package com.viel.aplayer.library.vfs

import android.content.Context
import android.os.ParcelFileDescriptor
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.source.LibrarySourceProviderFactory
import java.io.InputStream

// 详尽的中文注释：VfsFileReader 是解析器、封面恢复和字幕查找共享的读流门面，调用方只传 rootId/sourcePath，不再持有 Provider 原生文件对象。
class VfsFileReader(
    context: Context,
    private val libraryRootDao: LibraryRootDao? = null,
    private val rootsById: Map<String, LibraryRootEntity> = emptyMap()
) {
    private val vfs = VirtualFileSystem(LibrarySourceProviderFactory(context.applicationContext))

    suspend fun open(file: FileRef): InputStream? {
        // 详尽的中文注释：扫描期 FileRef 已经带 rootId/sourcePath，清单解析和封面读取直接复用这两个标准字段。
        val root = rootFor(file.rootId) ?: return null
        return vfs.openInputStream(root, VfsPath(file.sourcePath))
    }

    suspend fun open(file: BookFileEntity): InputStream? {
        // 详尽的中文注释：入库后的媒体辅助类从 BookFileEntity 还原 VFS 节点，彻底替代各处自行回到 SAF Tree 的旧逻辑。
        val root = rootFor(file.rootId) ?: return null
        return vfs.openInputStream(root, VfsPath(file.sourcePath))
    }

    suspend fun openFileDescriptor(file: BookFileEntity): ParcelFileDescriptor? {
        // 详尽的中文注释：封面恢复和元数据恢复通过 VFS 打开只读 FD，不再在媒体层恢复 Provider 原生文件对象。
        val root = rootFor(file.rootId) ?: return null
        return vfs.openFileDescriptor(root, VfsPath(file.sourcePath))
    }

    suspend fun openFileDescriptor(file: FileRef): ParcelFileDescriptor? {
        // 为每一次改动添加详尽的中文注释：扫描期元数据/封面读取同样走 VFS FD，避免 FileRef 暴露或依赖 provider URI。
        val root = rootFor(file.rootId) ?: return null
        return vfs.openFileDescriptor(root, VfsPath(file.sourcePath))
    }

    suspend fun open(node: VfsNode): InputStream? {
        // 详尽的中文注释：目录枚举返回的 VfsNode 可以直接读流，避免业务层为临时文件再拼装 FileRef。
        return vfs.openInputStream(node)
    }

    suspend fun listChildren(rootId: String, sourcePath: String): List<VfsNode> {
        // 详尽的中文注释：同目录 sidecar、字幕和 txt 描述文件均通过 VFS 枚举父目录，避免业务层直接访问 Provider 原生目录。
        val root = rootFor(rootId) ?: return emptyList()
        val directory = vfs.resolve(root, VfsPath(sourcePath)) ?: return emptyList()
        return vfs.listChildren(directory)
    }

    private suspend fun rootFor(rootId: String): LibraryRootEntity? =
        rootsById[rootId] ?: libraryRootDao?.getRootById(rootId)
}
