package com.viel.aplayer.ui.home

import androidx.annotation.StringRes
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode

/**
 * LibraryUiState Model (Library Main Screen UI State)
 *
 * Aggregation class for library home screen UI state.
 * All data transformations (filtering, grouping, sorting, slicing) are completed in ViewModel's Flow pipeline.
 * Composable layer consumes pre-calculated fields directly, undertaking no business computation.
 */
data class LibraryUiState(
    /** List of all books on the bookshelf (unfiltered raw) */
    val audiobooks: List<BookWithProgress> = emptyList(),

    /** Current active filter type. null means combine pipeline has not produced first decision, UI should temporarily skip rendering FilterChip row */
    val selectedFilter: HomeFilter? = null,

    /** List of filtered books according to current filter */
    val filteredAudiobooks: List<BookWithProgress> = emptyList(),

    /** Filtered books grouped by author, used for author-based display in LazyColumn */
    val groupedByAuthor: Map<String, List<BookWithProgress>> = emptyMap(),

    /** Book list for "recent" section (NotStarted -> recently added; InProgress -> recently played) */
    val recentBooks: List<BookWithProgress> = emptyList(),

    /**
     * Recent Title Resource ID (Recent Section Header Text)
     *
     * Title string resource ID for the "recent" section.
     * 0 means recent section is not displayed under current filter.
     */
    @param:StringRes val recentTitleRes: Int = 0,

    /** Whether to show "recent" horizontal scrolling section */
    val shouldShowRecentBooks: Boolean = false,

    /** Current floating layer glass effect mode, shared by homepage Dialog and player BottomSheet */
    // Default value of UiState first frame references settings model default value, avoiding hardcoded Material in UI state layer.
    val glassEffectMode: GlassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE
)
