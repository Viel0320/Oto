package com.viel.oto.media

import android.net.Uri
import com.viel.oto.data.entity.BookFileEntity

object VfsPlaybackUri {
    const val SCHEME: String = "oto-vfs"
    private const val BOOK_FILE_AUTHORITY = "book-file"
    private const val LOCAL_BOOK_FILE_AUTHORITY = "oto-book-file"
    private const val LOCAL_SCHEME = "content"

    /**
     * Builds the stable Media3 URI for a book file.
     *
     * Direct playback intentionally uses a content scheme so Media3 applies its local-playback
     * LoadControl branch while Oto still resolves the file through the same VFS data source.
     */
    fun fromBookFile(
        file: BookFileEntity,
        bufferPolicy: PlaybackBufferPolicy = PlaybackBufferPolicy.Buffered
    ): Uri =
        when (bufferPolicy) {
            PlaybackBufferPolicy.Buffered -> bufferedBookFileUri(file)
            PlaybackBufferPolicy.Direct -> directBookFileUri(file)
        }

    /**
     * Extracts the book file identity from both legacy buffered VFS URIs and direct local-policy URIs.
     */
    fun bookFileId(uri: Uri): String? =
        when {
            uri.scheme == SCHEME &&
                uri.authority == BOOK_FILE_AUTHORITY -> uri.pathSegments.firstOrNull()
            uri.scheme == LOCAL_SCHEME &&
                uri.authority == LOCAL_BOOK_FILE_AUTHORITY -> uri.pathSegments.firstOrNull()
            else -> null
        }

    private fun bufferedBookFileUri(file: BookFileEntity): Uri =
        Uri.Builder()
            .scheme(SCHEME)
            .authority(BOOK_FILE_AUTHORITY)
            .appendPath(file.id)
            .build()

    private fun directBookFileUri(file: BookFileEntity): Uri =
        Uri.Builder()
            .scheme(LOCAL_SCHEME)
            .authority(LOCAL_BOOK_FILE_AUTHORITY)
            .appendPath(file.id)
            .build()
}
