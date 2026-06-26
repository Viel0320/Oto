package com.viel.oto.ui.home

import androidx.annotation.StringRes
import com.viel.oto.application.library.home.HomeBookItem
import com.viel.oto.shared.settings.AppSettings
import com.viel.oto.shared.settings.GlassEffectMode
import com.viel.oto.shared.settings.HomeBookStatusFilter
import com.viel.oto.shared.settings.HomeFilter
import com.viel.oto.shared.settings.HomeSortDirection
import com.viel.oto.shared.settings.HomeSortRule
import com.viel.oto.shared.settings.HomeViewStyle
import com.viel.oto.shared.settings.ThemeMode

/**
 * Library Main Screen UI State.
 *
 * Aggregation class for library home screen UI state.
 * All catalog transformations are completed in LibraryViewModel's Flow pipeline before this immutable state reaches Compose.
 * Composable layer consumes pre-calculated fields directly, undertaking no business computation.
 */
data class LibraryUiState(
    /** unfiltered raw. */
    val audiobooks: List<HomeBookItem> = emptyList(),

    val hasRegisteredLibraryRoots: Boolean = false,

    /** Current active filter type. null means combine pipeline has not produced first decision, UI should temporarily skip rendering FilterChip row */
    val selectedFilter: HomeFilter? = null,

    val homeBookStatusFilter: HomeBookStatusFilter = HomeBookStatusFilter.All,

    /** List of filtered books according to current filter and current Home sorting rule */
    val filteredAudiobooks: List<HomeBookItem> = emptyList(),

    val groupedAudiobooks: Map<String, List<HomeBookItem>> = emptyMap(),

    /** Book list for "recent" section (NotStarted -> recently added; InProgress -> recently played) */
    val recentBooks: List<HomeBookItem> = emptyList(),

    /**
     * Recent Section Header Text.
     *
     * Title string resource ID for the "recent" section.
     * 0 means recent section is not displayed under current filter.
     */
    @param:StringRes val recentTitleRes: Int = 0,

    /** Whether to show "recent" horizontal scrolling section */
    val shouldShowRecentBooks: Boolean = false,

    /** Current floating layer glass effect mode, shared by homepage Dialog and player BottomSheet */
    val glassEffectMode: GlassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE,

    val homeViewStyle: HomeViewStyle = HomeViewStyle.List,

    val homeSortRule: HomeSortRule = HomeSortRule.Author,

    val homeSortDirection: HomeSortDirection = HomeSortDirection.Ascending,

    val themeMode: ThemeMode = ThemeMode.System,
    val isDynamicColorEnabled: Boolean = true,
    val isAmoledEnabled: Boolean = false,
    val homeDialogState: HomeDialogState = HomeDialogState.None
)
