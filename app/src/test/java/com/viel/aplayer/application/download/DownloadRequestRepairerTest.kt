package com.viel.aplayer.application.download

import com.viel.aplayer.data.entity.BookFileEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadRequestRepairerTest {
    @Test
    fun `repairer should return only files missing from download index`() = runBlocking {
        val repairer = DownloadRequestRepairer(
            downloadIndexSnapshotReader = StaticDownloadIndexSnapshotReader(
                existingFileIds = setOf("file-1", "file-3")
            )
        )

        val missing = repairer.findMissingFiles(
            bookId = "book-1",
            files = listOf(testFile("file-1"), testFile("file-2"), testFile("file-3"))
        )

        assertEquals(listOf("file-2"), missing.map { file -> file.id })
    }

    private class StaticDownloadIndexSnapshotReader(
        private val existingFileIds: Set<String>
    ) : DownloadIndexSnapshotReader {
        override suspend fun getSnapshot(fileId: String): FileDownloadSnapshot? =
            if (fileId in existingFileIds) {
                FileDownloadSnapshot(fileId, FileDownloadState.QUEUED, 0L, 100L)
            } else {
                null
            }
    }

    private fun testFile(id: String): BookFileEntity =
        BookFileEntity(
            id = id,
            bookId = "book-1",
            rootId = "root-1",
            index = id.takeLast(1).toInt(),
            sourcePath = "$id.mp3",
            sourceIdentity = id,
            displayName = "$id.mp3",
            durationMs = 1_000L,
            fileSize = 100L,
            lastModified = 0L
        )
}
