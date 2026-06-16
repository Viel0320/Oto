package com.viel.aplayer.ui.home

// Import HomeFilter and HomeBookStatusFilter (Brings in the relocated type-safe filter enums from the data store layer)
// Theme Mode Selection (Support theme mode preference settings) Added ThemeMode import to access selected theme configurations.
import androidx.annotation.StringRes
import com.viel.aplayer.application.library.home.HomeBookItem
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.data.store.HomeBookStatusFilter
import com.viel.aplayer.data.store.HomeFilter
import com.viel.aplayer.data.store.HomeSortDirection
import com.viel.aplayer.data.store.HomeSortRule
import com.viel.aplayer.data.store.HomeViewStyle
import com.viel.aplayer.data.store.ThemeMode

/**
 * LibraryUiState Model (Library Main Screen UI State)
 *
 * Aggregation class for library home screen UI state.
 * Catalog transformations are completed by the Home application read model before this UI state is assembled.
 * Composable layer consumes pre-calculated fields directly, undertaking no business computation.
 */
data class LibraryUiState(
    /** List of all books on the bookshelf (unfiltered raw) */
    val audiobooks: List<HomeBookItem> = emptyList(),

    // Registered Library Roots State (Track whether any media source has been added)
    // Home uses this alongside the catalog list so the add-library FAB appears when the app has no roots or when existing roots have produced an empty book list.
    val hasRegisteredLibraryRoots: Boolean = false,

    /** Current active filter type. null means combine pipeline has not produced first decision, UI should temporarily skip rendering FilterChip row */
    val selectedFilter: HomeFilter? = null,

    // Home Book Status Filter State (Expose the active availability filter to the Home view dialog)
    // The default remains All so the initial UI state never hides visible books before DataStore emits.
    val homeBookStatusFilter: HomeBookStatusFilter = HomeBookStatusFilter.All,

    /** List of filtered books according to current filter and current Home sorting rule */
    val filteredAudiobooks: List<HomeBookItem> = emptyList(),

    // Home Grouped Catalog (Filtered books grouped by the active Home sort rule)
    // The map keeps insertion order from the ViewModel's script-clustered sort so section headers render in the same order as the selected rule.
    val groupedAudiobooks: Map<String, List<HomeBookItem>> = emptyMap(),

    /** Book list for "recent" section (NotStarted -> recently added; InProgress -> recently played) */
    val recentBooks: List<HomeBookItem> = emptyList(),

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
    // The UI consumes this directly to switch between adaptive listgroup columns and single-line Cardgroup carousels.
    val homeViewStyle: HomeViewStyle = HomeViewStyle.List,

    // Home Sort Rule State (Expose the selected Home grouping pivot and script-clustered order)
    // The app bar dialog reads this value for selected controls, while content consumes the already-grouped catalog data.
    val homeSortRule: HomeSortRule = HomeSortRule.Author,

    // Home Sort Direction State (Expose ascending or descending preference for items inside each script cluster)
    // Cluster order remains fixed in the sort policy, so the UI only needs this value for the Home view dialog selected state.
    val homeSortDirection: HomeSortDirection = HomeSortDirection.Ascending,

    // Theme Mode Config (Active theme configuration preference, e.g. System, Light, or Dark) Added theme mode field to home library UI state.
    val themeMode: ThemeMode = ThemeMode.System,
    // Dynamic Color State Flag (Track theme color selection preference) Holds dynamic color setting state.
    val isDynamicColorEnabled: Boolean = true,
    // Home Dialog State (Expose page-level modal event holder to ViewModel)
    // Keeps dialog selection in the UI state so that dialog visibility survives configuration changes.
    val homeDialogState: HomeDialogState = HomeDialogState.None
)
