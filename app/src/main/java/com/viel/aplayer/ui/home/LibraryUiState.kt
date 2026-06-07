package com.viel.aplayer.ui.home

import androidx.annotation.StringRes
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.HomeSortRule
import com.viel.aplayer.data.store.HomeViewStyle
// Theme Mode Selection (Support theme mode preference settings) Added ThemeMode import to access selected theme configurations.
import com.viel.aplayer.data.store.ThemeMode

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

    /** List of filtered books according to current filter and current Home sorting rule */
    val filteredAudiobooks: List<BookWithProgress> = emptyList(),

    // Home Grouped Catalog (Filtered books grouped by the active Home sort rule)
    // The map keeps insertion order from the ViewModel's pinyin-descending sort so section headers render in the same order as the selected rule.
    val groupedAudiobooks: Map<String, List<BookWithProgress>> = emptyMap(),

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
    val glassEffectMode: GlassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE,

    // Home View Style State (Expose the selected Home catalog renderer to the Composable layer)
    // The UI consumes this directly to switch between adaptive listgroup columns and single-line cardgroup carousels.
    val homeViewStyle: HomeViewStyle = HomeViewStyle.List,

    // Home Sort Rule State (Expose the selected Home grouping and pinyin-descending order)
    // The app bar dialog reads this value for selected controls, while content consumes the already-grouped catalog data.
    val homeSortRule: HomeSortRule = HomeSortRule.Author,

    // Theme Mode Config (Active theme configuration preference, e.g. System, Light, or Dark) Added theme mode field to home library UI state.
    val themeMode: ThemeMode = ThemeMode.System,
    // Dynamic Color State Flag (Track theme color selection preference) Holds dynamic color setting state.
    val isDynamicColorEnabled: Boolean = true
)
