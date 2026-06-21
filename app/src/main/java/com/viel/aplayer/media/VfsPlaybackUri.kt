package com.viel.aplayer.media

import android.net.Uri
import com.viel.aplayer.data.entity.BookFileEntity

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
