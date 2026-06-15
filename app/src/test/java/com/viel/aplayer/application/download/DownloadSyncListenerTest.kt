package com.viel.aplayer.application.download

import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

@UnstableApi
class DownloadSyncListenerTest {
    @Test
    fun `listener should resolve file id and reconcile parent book`() = runBlocking {
        val reconciled = mutableListOf<String>()
        var progressPollerStarts = 0
        val listener = DownloadSyncListener(
            downloadBookIdResolver = StaticDownloadBookIdResolver(mapOf("file-1" to "book-1")),
            downloadBookReconcilerProvider = {
                object : DownloadBookReconciler {
                    override suspend fun reconcileBook(bookId: String) {
                        reconciled += bookId
                    }
                }
            },
            progressPollerStarter = { progressPollerStarts += 1 },
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        listener.handleFileDownloadEvent("file-1")
        listener.handleFileDownloadEvent("orphan-file")

        // Realtime File Event Mapping (Start progress polling and reconcile only known parent books)
        // Media3 reports DownloadRequest.id sparsely, so the listener both wakes byte polling and resolves the parent book before touching Room metadata.
        assertEquals(2, progressPollerStarts)
        assertEquals(listOf("book-1"), reconciled)
    }

    @Test
    fun `removed download should clear parent task without reconciliation`() = runBlocking {
        val reconciled = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val listener = DownloadSyncListener(
            downloadBookIdResolver = StaticDownloadBookIdResolver(mapOf("file-1" to "book-1")),
            downloadBookReconcilerProvider = {
                object : DownloadBookReconciler {
                    override suspend fun reconcileBook(bookId: String) {
                        reconciled += bookId
                    }
                }
            },
            downloadBookRemovalHandler = { bookId -> removed += bookId },
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        listener.handleFileRemoval("file-1")
        listener.handleFileRemoval("orphan-file")

        // Removed Download Cleanup (Do not convert removed DownloadIndex rows into queued aggregates)
        // The first delete click removes Media3 rows, so removal callbacks must clear metadata and avoid the normal reconcile path.
        assertEquals(listOf("book-1"), removed)
        assertEquals(emptyList<String>(), reconciled)
    }

    private class StaticDownloadBookIdResolver(
        private val ids: Map<String, String>
    ) : DownloadBookIdResolver {
        override suspend fun getBookIdByFileId(fileId: String): String? = ids[fileId]
    }
}
