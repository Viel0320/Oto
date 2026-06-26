package com.viel.oto.application.library.home

import com.viel.oto.application.library.LibraryBookSourceType
import com.viel.oto.application.library.LibraryBookStatus
import com.viel.oto.application.library.LibraryReadStatus
import com.viel.oto.shared.model.HomeSortDirection
import com.viel.oto.shared.model.HomeSortRule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks mixed-script bookshelf ordering.
 *
 * Runs through Robolectric because production sorting uses android.icu.text.Collator instead of java.text.Collator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HomeCatalogSortPolicyTest {

    @Test
    fun `sort clusters Chinese Japanese Korean English and other scripts in fixed order`() {
        val books = listOf(
            book(id = "other", author = "\u03A9mega"),
            book(id = "english", author = "Alpha"),
            book(id = "korean", author = "\uAC00\uB098\uB2E4"),
            book(id = "japanese", author = "\u3042\u304A\u3044"),
            book(id = "chinese", author = "\u5F20\u4E09")
        )

        val sortedIds = HomeCatalogSortPolicy.sort(books, HomeSortRule.Author).map { book -> book.id }

        assertEquals(listOf("chinese", "japanese", "korean", "english", "other"), sortedIds)
    }

    @Test
    fun `han script always belongs to the Chinese cluster before kana names`() {
        val books = listOf(
            book(id = "kana", author = "\u3042\u304A\u3044"),
            book(id = "han", author = "\u5C71\u7530"),
            book(id = "english", author = "Yamada")
        )

        val sortedIds = HomeCatalogSortPolicy.sort(books, HomeSortRule.Author).map { book -> book.id }

        assertEquals(listOf("han", "kana", "english"), sortedIds)
    }

    @Test
    fun `blank grouping keys render as Unknown but sort in the other cluster`() {
        val books = listOf(
            book(id = "blank", author = " "),
            book(id = "english", author = "Alice")
        )

        val sorted = HomeCatalogSortPolicy.sort(books, HomeSortRule.Author)

        assertEquals(listOf("english", "blank"), sorted.map { book -> book.id })
        assertEquals("Unknown", HomeCatalogSortPolicy.groupLabel(sorted.last(), HomeSortRule.Author))
    }

    @Test
    fun `sort rule selects narrator and series keys through the same cluster policy`() {
        val books = listOf(
            book(id = "english-series", author = "A", narrator = "A", series = "Alpha"),
            book(id = "korean-series", author = "A", narrator = "A", series = "\uAC00\uB098\uB2E4"),
            book(id = "chinese-series", author = "A", narrator = "A", series = "\u4E09\u4F53")
        )

        val sortedIds = HomeCatalogSortPolicy.sort(books, HomeSortRule.Series).map { book -> book.id }

        assertEquals(listOf("chinese-series", "korean-series", "english-series"), sortedIds)
    }

    @Test
    fun `descending direction reverses only items inside each fixed script cluster`() {
        val books = listOf(
            book(id = "english-a", author = "Alice"),
            book(id = "english-b", author = "Bob"),
            book(id = "korean-a", author = "\uAC00\uB098\uB2E4"),
            book(id = "korean-b", author = "\uB098\uB2E4\uB77C"),
            book(id = "chinese-a", author = "\u738B\u4E94"),
            book(id = "chinese-b", author = "\u5F20\u4E09")
        )

        val sortedIds = HomeCatalogSortPolicy.sort(
            books = books,
            sortRule = HomeSortRule.Author,
            sortDirection = HomeSortDirection.Descending
        ).map { book -> book.id }

        assertEquals(
            listOf("chinese-b", "chinese-a", "korean-b", "korean-a", "english-b", "english-a"),
            sortedIds
        )
    }

    @Test
    fun `organize returns sorted books and grouped sections from one policy pass`() {
        val books = listOf(
            book(id = "blank", author = " "),
            book(id = "english-b", author = "Bob"),
            book(id = "english-a", author = "Alice"),
            book(id = "chinese", author = "\u5F20\u4E09")
        )

        val organization = HomeCatalogSortPolicy.organize(books, HomeSortRule.Author)

        assertEquals(listOf("chinese", "english-a", "english-b", "blank"), organization.sortedBooks.map { book -> book.id })
        assertEquals(listOf("\u5F20\u4E09", "Alice", "Bob", "Unknown"), organization.groupedBooks.keys.toList())
        assertEquals(listOf("english-a"), organization.groupedBooks.getValue("Alice").map { book -> book.id })
    }

    private fun book(
        id: String,
        author: String,
        title: String = id,
        narrator: String = "",
        series: String = ""
    ): HomeBookItem {
        return HomeBookItem(
            id = id,
            rootId = "root",
            sourceType = LibraryBookSourceType.SINGLE_AUDIO,
            status = LibraryBookStatus.READY,
            title = title,
            author = author,
            narrator = narrator,
            description = "",
            year = "",
            series = series,
            totalDurationMs = 0L,
            totalFileSize = 0L,
            coverPath = null,
            thumbnailPath = null,
            lastScannedAt = 0L,
            addedAt = 0L,
            readStatus = LibraryReadStatus.NOT_STARTED,
            progressPercent = 0,
            lastPlayedAt = 0L,
            isFinished = false,
            isInProgress = false,
            isNotStarted = true
        )
    }
}
