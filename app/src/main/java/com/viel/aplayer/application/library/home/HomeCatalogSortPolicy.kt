package com.viel.aplayer.application.library.home

import android.icu.text.Collator
import com.viel.aplayer.application.library.home.HomeCatalogSortPolicy.organize
import com.viel.aplayer.shared.settings.HomeSortDirection
import com.viel.aplayer.shared.settings.HomeSortRule
import java.util.Locale

/**
 * Home Catalog Organization (Sorted rows plus section groups from one projection pass)
 *
 * Carries both Home list outputs so callers can avoid sorting rows and then recomputing group labels
 * over the same batch immediately afterward.
 */
data class HomeCatalogOrganization(
    val sortedBooks: List<HomeBookItem>,
    val groupedBooks: Map<String, List<HomeBookItem>>
)

/**
 * Home Catalog Sort Policy (Orders Home catalog sections by script cluster and locale collation)
 *
 * The policy keeps bookshelf ordering out of LibraryViewModel while making the mixed-script behavior explicit:
 * Chinese/Han, Japanese kana, Korean hangul, English/Latin, then all remaining scripts.
 */
object HomeCatalogSortPolicy {
    private const val UNKNOWN_GROUP_LABEL = "Unknown"
    private val threadLocalCollators: ThreadLocal<Map<HomeScriptCluster, Collator>> = ThreadLocal.withInitial {
        HomeScriptCluster.entries.associateWith { cluster -> cluster.createCollator() }
    }

    /**
     * Organizes Home catalog rows with one cached projection pass.
     *
     * The sorted list and grouped map are derived from the same internal projection instances, so
     * Home avoids re-trimming metadata keys and re-resolving visible section labels after sorting.
     */
    fun organize(
        books: List<HomeBookItem>,
        sortRule: HomeSortRule,
        sortDirection: HomeSortDirection = HomeSortDirection.Ascending
    ): HomeCatalogOrganization {
        val projections = sortedProjections(
            books = books,
            sortRule = sortRule,
            sortDirection = sortDirection
        )
        val sortedBooks = ArrayList<HomeBookItem>(projections.size)
        val groupedBooks = linkedMapOf<String, MutableList<HomeBookItem>>()
        projections.forEach { projection ->
            sortedBooks += projection.book
            groupedBooks.getOrPut(projection.groupLabel) { mutableListOf() } += projection.book
        }
        return HomeCatalogOrganization(
            sortedBooks = sortedBooks,
            groupedBooks = groupedBooks
        )
    }

    /**
     * Sorts Home catalog rows while reusing thread-local ICU collators.
     *
     * This remains available for callers that only need ordering. Callers that also need section
     * groups should use [organize] so sort keys and group labels are projected once per batch.
     */
    fun sort(
        books: List<HomeBookItem>,
        sortRule: HomeSortRule,
        sortDirection: HomeSortDirection = HomeSortDirection.Ascending
    ): List<HomeBookItem> {
        return sortedProjections(
            books = books,
            sortRule = sortRule,
            sortDirection = sortDirection
        ).map { projection -> projection.book }
    }

    fun groupLabel(book: HomeBookItem, sortRule: HomeSortRule): String {
        return book.sortKey(sortRule).ifBlank { UNKNOWN_GROUP_LABEL }
    }

    private fun sortedProjections(
        books: List<HomeBookItem>,
        sortRule: HomeSortRule,
        sortDirection: HomeSortDirection
    ): List<HomeSortProjection> {
        val collators = checkNotNull(threadLocalCollators.get())
        // Script Cluster Projection (Cache sort keys before comparison)
        // Kotlin's comparator can invoke comparisons repeatedly, so each book receives one immutable projection containing
        // the selected metadata key, its script cluster, display fallback, and deterministic tie breakers.
        return books
            .map { book -> HomeSortProjection(book = book, sortRule = sortRule) }
            .sortedWith { left, right ->
                compareProjections(left, right, sortDirection, collators)
            }
    }

    private fun compareProjections(
        left: HomeSortProjection,
        right: HomeSortProjection,
        sortDirection: HomeSortDirection,
        collators: Map<HomeScriptCluster, Collator>
    ): Int {
        val cluster = left.cluster.rank.compareTo(right.cluster.rank)
        if (cluster != 0) return cluster

        val collator = collators.getValue(left.cluster)
        // In-Cluster Direction Application (Flip locale collation only after the script cluster rank is fixed)
        // This preserves the required C -> J -> K -> E -> Other stream while still supporting ascending and descending inside each cluster.
        val directionMultiplier = if (sortDirection == HomeSortDirection.Ascending) 1 else -1
        val primary = collator.compare(left.groupLabel, right.groupLabel) * directionMultiplier
        if (primary != 0) return primary

        val title = collator.compare(left.title, right.title) * directionMultiplier
        if (title != 0) return title

        return left.id.compareTo(right.id) * directionMultiplier
    }

    private fun HomeBookItem.sortKey(sortRule: HomeSortRule): String {
        return when (sortRule) {
            HomeSortRule.Author -> author.trim()
            HomeSortRule.Narrator -> narrator.trim()
            HomeSortRule.Series -> series.trim()
        }
    }

    private data class HomeSortProjection(
        val book: HomeBookItem,
        val sortRule: HomeSortRule
    ) {
        private val rawGroupKey = book.sortKey(sortRule)
        val cluster = HomeScriptCluster.from(rawGroupKey)
        val groupLabel = rawGroupKey.ifBlank { UNKNOWN_GROUP_LABEL }
        val title = book.title.trim()
        val id = book.id
    }

    private enum class HomeScriptCluster(
        val rank: Int,
        private val locale: Locale
    ) {
        Chinese(rank = 0, locale = Locale.CHINA),
        Japanese(rank = 1, locale = Locale.JAPAN),
        Korean(rank = 2, locale = Locale.KOREA),
        English(rank = 3, locale = Locale.ENGLISH),
        Other(rank = 4, locale = Locale.ROOT);

        fun createCollator(): Collator {
            return Collator.getInstance(locale).apply {
                // Locale Primary Strength (Ignore case and accent differences inside the same script cluster)
                // Home catalog grouping should keep visually equivalent creator names together while id remains the final tie breaker.
                strength = Collator.PRIMARY
            }
        }

        companion object {
            fun from(text: String): HomeScriptCluster {
                val codePoints = text.codePoints().iterator()
                while (codePoints.hasNext()) {
                    // Significant Script Scan (Ignore punctuation, spaces, and inherited marks until a real script appears)
                    // This lets names such as "[Alice]" or "01 가나다" still enter the English or Korean cluster instead of being trapped as Other.
                    when (Character.UnicodeScript.of(codePoints.nextInt())) {
                        Character.UnicodeScript.HAN,
                        Character.UnicodeScript.BOPOMOFO -> return Chinese
                        Character.UnicodeScript.HIRAGANA,
                        Character.UnicodeScript.KATAKANA -> return Japanese
                        Character.UnicodeScript.HANGUL -> return Korean
                        Character.UnicodeScript.LATIN -> return English
                        Character.UnicodeScript.COMMON,
                        Character.UnicodeScript.INHERITED,
                        Character.UnicodeScript.UNKNOWN -> Unit
                        else -> return Other
                    }
                }
                return Other
            }
        }
    }
}
