package com.viel.oto.application.usecase

import com.viel.oto.data.book.BookCatalogGateway
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.BookProgressEntity
import com.viel.oto.data.entity.BookWithProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the heuristic recommendation scoring of [GetRelatedBooksUseCase] through its public
 * invoke entry point. The longest-common-substring, Chinese-sequence, series, author, narrator, and
 * filler branches are private, so they are covered indirectly via the ordered heuristic output.
 */
class GetRelatedBooksUseCaseTest {

    @Test
    fun `exact title match outranks weaker signals`() = runBlocking {
        val current = book(id = "cur", title = "Dune")
        val exact = book(id = "exact", title = "Dune")
        val unrelated = book(id = "other", title = "Foundation")
        val useCase = GetRelatedBooksUseCase(
            FakeCatalogGateway(currentBook = current, allBooks = listOf(current, exact, unrelated))
        )

        val heuristic = useCase("cur", author = "", narrator = "").first().heuristicRecommended

        // exact match (score 40) ranks first; unrelated still appears as filler since matched < 5.
        assertEquals("exact", heuristic.first().id)
        assertTrue(heuristic.any { it.id == "other" })
    }

    @Test
    fun `lower sequence index is penalised below a higher one`() = runBlocking {
        // current is volume 2; volume 1 (prequel) should be pushed below volume 3.
        val current = book(id = "cur", title = "三体 第二部")
        val prequel = book(id = "vol1", title = "三体 第一部")
        val sequel = book(id = "vol3", title = "三体 第三部")
        val useCase = GetRelatedBooksUseCase(
            FakeCatalogGateway(currentBook = current, allBooks = listOf(current, prequel, sequel))
        )

        val heuristic = useCase("cur", author = "", narrator = "").first().heuristicRecommended
        val orderedIds = heuristic.map { it.id }

        assertTrue(orderedIds.indexOf("vol3") < orderedIds.indexOf("vol1"))
    }

    @Test
    fun `matching series adds score above an unrelated book`() = runBlocking {
        val current = book(id = "cur", title = "Solo", series = "Saga")
        val sameSeries = book(id = "same", title = "Totally Different", series = "Saga")
        val unrelated = book(id = "none", title = "Nothing Alike", series = "Elsewhere")
        val useCase = GetRelatedBooksUseCase(
            FakeCatalogGateway(currentBook = current, allBooks = listOf(current, sameSeries, unrelated))
        )

        val heuristic = useCase("cur", author = "", narrator = "").first().heuristicRecommended
        val orderedIds = heuristic.map { it.id }

        // same-series scores 35; unrelated scores ~0 and only appears as filler afterwards.
        assertTrue(orderedIds.indexOf("same") < orderedIds.indexOf("none"))
    }

    @Test
    fun `null current book yields empty heuristic recommendations`() = runBlocking {
        val useCase = GetRelatedBooksUseCase(
            FakeCatalogGateway(currentBook = null, allBooks = emptyList())
        )

        val related = useCase("missing", author = "", narrator = "").first()

        assertTrue(related.heuristicRecommended.isEmpty())
    }

    @Test
    fun `unmatched library is filled with recently added books up to five`() = runBlocking {
        val current = book(id = "cur", title = "Zzz Unique Title")
        val fillers = (1..7).map { index -> book(id = "f$index", title = "Filler $index", addedAt = index.toLong()) }
        val useCase = GetRelatedBooksUseCase(
            FakeCatalogGateway(currentBook = current, allBooks = listOf(current) + fillers)
        )

        val heuristic = useCase("cur", author = "", narrator = "").first().heuristicRecommended

        // No title/series/author/narrator overlap, so the list is pure filler capped at five.
        assertEquals(5, heuristic.size)
        assertTrue(heuristic.none { it.id == "cur" })
    }

    private fun book(
        id: String,
        title: String,
        author: String = "",
        narrator: String = "",
        series: String = "",
        year: String = "",
        addedAt: Long = 0L
    ): BookEntity =
        BookEntity(
            id = id,
            rootId = "root",
            sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO,
            title = title,
            author = author,
            narrator = narrator,
            series = series,
            year = year,
            totalDurationMs = 1_000L,
            addedAt = addedAt
        )

    private class FakeCatalogGateway(
        private val currentBook: BookEntity?,
        allBooks: List<BookEntity>
    ) : BookCatalogGateway {

        private val withProgress: List<BookWithProgress> =
            allBooks.map { BookWithProgress(book = it, progress = null) }

        override val audiobooks: Flow<List<BookWithProgress>> = flowOf(withProgress)

        override suspend fun getBookById(id: String): BookEntity? =
            withProgress.firstOrNull { it.book.id == id }?.book

        override fun observeBookById(id: String): Flow<BookEntity?> =
            flowOf(currentBook?.takeIf { it.id == id })

        override fun searchAudiobooks(query: String): Flow<List<BookWithProgress>> = flowOf(emptyList())

        override fun filterByYear(year: String): Flow<List<BookWithProgress>> = flowOf(emptyList())

        override fun filterByAuthor(author: String): Flow<List<BookWithProgress>> = flowOf(emptyList())

        override fun filterByAuthorLimited(
            author: String,
            excludeId: String,
            limit: Int
        ): Flow<List<BookWithProgress>> = flowOf(emptyList())

        override fun filterByNarrator(narrator: String): Flow<List<BookWithProgress>> = flowOf(emptyList())

        override fun filterByNarratorLimited(
            narrator: String,
            excludeId: String,
            limit: Int
        ): Flow<List<BookWithProgress>> = flowOf(emptyList())

        override fun getRecentlyAdded(limit: Int): Flow<List<BookWithProgress>> = flowOf(emptyList())

        override fun getRecentlyAddedExclusive(
            currentId: String,
            authors: List<String>,
            narrators: List<String>,
            limit: Int
        ): Flow<List<BookWithProgress>> = flowOf(emptyList())

        override suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity> = emptyList()

        override suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity> = emptyList()
    }
}
