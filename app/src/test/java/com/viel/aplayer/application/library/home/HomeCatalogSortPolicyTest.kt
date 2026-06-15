package com.viel.aplayer.application.library.home

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.store.HomeSortDirection
import com.viel.aplayer.data.store.HomeSortRule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home Catalog Sort Policy Test (Locks mixed-script bookshelf ordering)
 *
 * Runs through Robolectric because production sorting uses android.icu.text.Collator instead of java.text.Collator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class HomeCatalogSortPolicyTest {

    @Test
    fun `sort clusters Chinese Japanese Korean English and other scripts in fixed order`() {
        val books = listOf(
            book(id = "other", author = "Ωmega"),
            book(id = "english", author = "Alpha"),
            book(id = "korean", author = "가나다"),
            book(id = "japanese", author = "あおい"),
            book(id = "chinese", author = "张三")
        )

        val sortedIds = HomeCatalogSortPolicy.sort(books, HomeSortRule.Author).map { book -> book.id }

        // Fixed Cluster Merge Order (Preserve the requested C -> J -> K -> E -> Other mixed stream)
        // The initial list is intentionally reversed so the assertion fails if sorting only keeps input order.
        assertEquals(listOf("chinese", "japanese", "korean", "english", "other"), sortedIds)
    }

    @Test
    fun `han script always belongs to the Chinese cluster before kana names`() {
        val books = listOf(
            book(id = "kana", author = "あおい"),
            book(id = "han", author = "山田"),
            book(id = "english", author = "Yamada")
        )

        val sortedIds = HomeCatalogSortPolicy.sort(books, HomeSortRule.Author).map { book -> book.id }

        // Han Cluster Rule (Treat all Han characters as Chinese for deterministic catalog grouping)
        // This intentionally avoids guessing whether a Han-only name is Chinese or Japanese from context that the data model does not carry.
        assertEquals(listOf("han", "kana", "english"), sortedIds)
    }

    @Test
    fun `blank grouping keys render as Unknown but sort in the other cluster`() {
        val books = listOf(
            book(id = "blank", author = " "),
            book(id = "english", author = "Alice")
        )

        val sorted = HomeCatalogSortPolicy.sort(books, HomeSortRule.Author)

        // Blank Metadata Cluster Rule (Keep missing authors out of the English cluster despite the visible Unknown fallback)
        // The display label remains stable for UI headers while the raw empty key places the item after known Latin names.
        assertEquals(listOf("english", "blank"), sorted.map { book -> book.id })
        assertEquals("Unknown", HomeCatalogSortPolicy.groupLabel(sorted.last(), HomeSortRule.Author))
    }

    @Test
    fun `sort rule selects narrator and series keys through the same cluster policy`() {
        val books = listOf(
            book(id = "english-series", author = "A", narrator = "A", series = "Alpha"),
            book(id = "korean-series", author = "A", narrator = "A", series = "가나다"),
            book(id = "chinese-series", author = "A", narrator = "A", series = "三体")
        )

        val sortedIds = HomeCatalogSortPolicy.sort(books, HomeSortRule.Series).map { book -> book.id }

        // Sort Rule Key Selection (Apply the same cluster ordering to the active Home pivot)
        // Author fields are identical here, so the assertion proves Series drives the selected ordering path.
        assertEquals(listOf("chinese-series", "korean-series", "english-series"), sortedIds)
    }

    @Test
    fun `descending direction reverses only items inside each fixed script cluster`() {
        val books = listOf(
            book(id = "english-a", author = "Alice"),
            book(id = "english-b", author = "Bob"),
            book(id = "korean-a", author = "가나다"),
            book(id = "korean-b", author = "하나"),
            book(id = "chinese-a", author = "王五"),
            book(id = "chinese-b", author = "张三")
        )

        val sortedIds = HomeCatalogSortPolicy.sort(
            books = books,
            sortRule = HomeSortRule.Author,
            sortDirection = HomeSortDirection.Descending
        ).map { book -> book.id }

        // Fixed Cluster Direction Split (Reverse comparisons inside each cluster without moving clusters themselves)
        // Chinese entries still precede Korean and English entries even though each cluster's local order is descending.
        assertEquals(
            listOf("chinese-b", "chinese-a", "korean-b", "korean-a", "english-b", "english-a"),
            sortedIds
        )
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
            // Update HomeCatalogSortPolicyTest: Change sourceType in helper to use type-safe AudiobookSchema.SourceType enum.
            sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO,
            status = AudiobookSchema.BookStatus.READY,
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
            readStatus = AudiobookSchema.ReadStatus.NOT_STARTED,
            progressPercent = 0,
            lastPlayedAt = 0L,
            isFinished = false,
            isInProgress = false,
            isNotStarted = true
        )
    }
}
