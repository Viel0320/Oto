package com.viel.aplayer.application.download

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.media.PlaybackRootLookup

/**
 * Downloadable Book File Selector (Centralize the manual-cache eligibility rule)
 *
 * Manual cache commands and orphan cleanup must agree that only remote audio rows can be submitted
 * to Media3 downloads; SAF files and manifest rows remain outside L1 manual cache ownership.
 */
class DownloadableBookFileSelector(
    private val downloadBookFileReader: DownloadBookFileReader,
    private val playbackRootLookup: PlaybackRootLookup
) {
    suspend fun remoteAudioFilesForBook(bookId: String): List<BookFileEntity> =
        downloadBookFileReader.getDownloadFilesForBook(bookId)
            .filter { file -> file.fileRole == AudiobookSchema.FileRole.AUDIO }
            .filter { file ->
                val root = playbackRootLookup.getRootById(file.rootId)
                root != null && root.sourceType != AudiobookSchema.LibrarySourceType.SAF
            }
            .sortedBy { file -> file.index }
}
