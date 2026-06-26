package com.viel.oto.application.download

import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.media.PlaybackRootLookup

/**
 * Centralize the manual-cache eligibility rule.
 *
 * Manual cache commands and orphan cleanup must agree that only remote audio rows can be submitted
 * to Media3 downloads; SAF files and manifest rows remain outside L1 manual cache ownership.
 */
class DownloadableBookFileSelector(
    private val downloadBookFileReader: DownloadBookFileReader,
    private val playbackRootLookup: PlaybackRootLookup
) {
    /**
     * Retrieve remote audio files eligible for manual download caching.
     * local. files and non-audio files (like playlists/manifests).
     * Employs a local cache for library roots to prevent redundant N+1 database queries.
     */
    suspend fun remoteAudioFilesForBook(bookId: String): List<BookFileEntity> {
        val files = downloadBookFileReader.getDownloadFilesForBook(bookId)
        val rootsCache = mutableMapOf<String, LibraryRootEntity?>()
        return files
            .filter { file -> file.fileRole == AudiobookSchema.FileRole.AUDIO }
            .filter { file ->
                val root = rootsCache.getOrPut(file.rootId) {
                    playbackRootLookup.getRootById(file.rootId)
                }
                root != null && root.sourceType != AudiobookSchema.LibrarySourceType.SAF
            }
            .sortedBy { file -> file.index }
    }
}
