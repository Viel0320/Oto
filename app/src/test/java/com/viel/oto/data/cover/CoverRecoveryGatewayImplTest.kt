package com.viel.oto.data.cover

import com.viel.oto.data.dao.BookDao
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Delegation contract for the consolidated self-heal seam.
 *
 * Pins that the gateway is a thin forwarder: the single-book and force paths delegate to the CoverSelfHealer
 * verbatim, and the catalog sweep replays only the bounded DAO candidate snapshot.
 */
class CoverRecoveryGatewayImplTest {

    @Test
    fun `triggerRecovery forwards the book to the self healer`() {
        val healer = RecordingCoverSelfHealer()
        val gateway = CoverRecoveryGatewayImpl(fakeBookDao(emptyList()), healer)

        gateway.triggerRecovery(sampleBook("b1"))

        assertEquals(listOf("b1"), healer.triggeredBookIds)
    }

    @Test
    fun `lightweight triggerRecovery forwards the id to the self healer`() {
        val healer = RecordingCoverSelfHealer()
        val gateway = CoverRecoveryGatewayImpl(fakeBookDao(emptyList()), healer)

        gateway.triggerRecovery(
            bookId = "b2",
            coverPath = null,
            thumbnailPath = null,
            lastScannedAt = 0L
        )

        assertEquals(listOf("b2"), healer.triggeredBookIds)
    }

    @Test
    fun `forceRegenerate forwards the id and returns the healer result`() = runBlocking {
        val healer = RecordingCoverSelfHealer().apply { forceResult = true }
        val gateway = CoverRecoveryGatewayImpl(fakeBookDao(emptyList()), healer)

        val rebuilt = gateway.forceRegenerate("b7")

        assertEquals("b7", healer.forcedBookId)
        assertTrue(rebuilt)
    }

    @Test
    fun `forceRegenerate propagates a false rebuild result`() = runBlocking {
        val healer = RecordingCoverSelfHealer().apply { forceResult = false }
        val gateway = CoverRecoveryGatewayImpl(fakeBookDao(emptyList()), healer)

        assertFalse(gateway.forceRegenerate("b8"))
    }

    @Test
    fun `recoverMissingCovers triggers self heal for every active book in order`() = runBlocking {
        val healer = RecordingCoverSelfHealer()
        val books = listOf(sampleBook("b1"), sampleBook("b2"), sampleBook("b3"))
        val gateway = CoverRecoveryGatewayImpl(fakeBookDao(books), healer)

        gateway.recoverMissingCovers()

        assertEquals(listOf("b1", "b2", "b3"), healer.triggeredBookIds)
    }

    @Test
    fun `recoverMissingCovers applies the startup sweep limit before triggering self heal`() = runBlocking {
        val healer = RecordingCoverSelfHealer()
        val books = listOf(sampleBook("b1"), sampleBook("b2"), sampleBook("b3"))
        val gateway = CoverRecoveryGatewayImpl(
            bookDao = fakeBookDao(books),
            coverSelfHealer = healer,
            sweepPolicy = CoverRecoverySweepPolicy(maxBooksPerSweep = 2, batchSize = 1, batchDelayMs = 0L)
        )

        gateway.recoverMissingCovers()

        assertEquals(listOf("b1", "b2"), healer.triggeredBookIds)
    }

    @Test
    fun `recoverMissingCovers does nothing when the catalog snapshot is empty`() = runBlocking {
        val healer = RecordingCoverSelfHealer()
        val gateway = CoverRecoveryGatewayImpl(fakeBookDao(emptyList()), healer)

        gateway.recoverMissingCovers()

        assertTrue(healer.triggeredBookIds.isEmpty())
    }

    private class RecordingCoverSelfHealer : CoverSelfHealer {
        val triggeredBookIds = mutableListOf<String>()
        var forcedBookId: String? = null
        var forceResult = true

        override fun checkAndTriggerCoverRegeneration(book: BookEntity) {
            triggeredBookIds += book.id
        }

        override fun checkAndTriggerCoverRegeneration(
            bookId: String,
            coverPath: String?,
            thumbnailPath: String?,
            lastScannedAt: Long
        ) {
            triggeredBookIds += bookId
        }

        override suspend fun forceRegenerateCover(bookId: String): Boolean {
            forcedBookId = bookId
            return forceResult
        }
    }

    /**
     * Reflective stub exposing only the snapshot query this gateway uses.
     * Mirrors the project's Proxy-based DAO faking so the test does not implement the full BookDao surface.
     */
    private fun fakeBookDao(books: List<BookEntity>): BookDao =
        Proxy.newProxyInstance(
            BookDao::class.java.classLoader,
            arrayOf(BookDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getAllBooksOnce" -> books
                "getCoverRecoveryCandidates" -> books.take(args?.firstOrNull() as Int)
                else -> throw UnsupportedOperationException("Unexpected DAO method: ${method.name}")
            }
        } as BookDao

    private fun sampleBook(id: String) =
        BookEntity(
            id = id,
            rootId = "root-1",
            sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO,
            title = "Title $id"
        )
}
