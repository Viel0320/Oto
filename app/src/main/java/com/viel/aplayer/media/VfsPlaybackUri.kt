package com.viel.aplayer.media

import android.net.Uri
import com.viel.aplayer.data.entity.BookFileEntity

// 为每一次改动添加详尽的中文注释：VfsPlaybackUri 是播放器内部专用地址格式，避免 Media3 队列继续暴露或依赖持久化的原始 SAF/远程 URI。
object VfsPlaybackUri {
    const val SCHEME: String = "aplayer-vfs"
    private const val BOOK_FILE_AUTHORITY = "book-file"

    fun fromBookFile(file: BookFileEntity): Uri =
        Uri.Builder()
            .scheme(SCHEME)
            .authority(BOOK_FILE_AUTHORITY)
            .appendPath(file.id)
            .build()

    fun bookFileId(uri: Uri): String? =
        uri.takeIf { it.scheme == SCHEME && it.authority == BOOK_FILE_AUTHORITY }
            ?.pathSegments
            ?.firstOrNull()
}
