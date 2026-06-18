package com.viel.aplayer.application.library.home

import android.net.Uri
import com.viel.aplayer.application.library.detail.DetailBookItem
import com.viel.aplayer.data.book.BookCatalogGateway
import com.viel.aplayer.data.book.BookMetadataGateway
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.metadata.MetadataRefreshGateway
import com.viel.aplayer.data.root.LibraryRootGateway
import com.viel.aplayer.data.scan.ScanScheduler
import com.viel.aplayer.data.search.SearchHistoryGateway
import com.viel.aplayer.shared.settings.AppSettings
import com.viel.aplayer.shared.settings.HomeBookStatusFilter
import com.viel.aplayer.shared.settings.HomeFilter
import com.viel.aplayer.shared.settings.HomeSortDirection
import com.viel.aplayer.shared.settings.HomeSortRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Home Book Item (Room-free catalog projection for the bookshelf)
 * Carries the metadata, progress, and status fields rendered or sorted by Home without exposing Room relationship rows.
 */
data class HomeBookItem(
    val id: String,
    val rootId: String,
    // Home Source Type Safe: Use AudiobookSchema.SourceType enum for type safety.
    val sourceType: AudiobookSchema.SourceType,
    // Book Status Type Safe: Use BookStatus enum for type safety.
    val status: AudiobookSchema.BookStatus,
    val title: String,
    val author: String,
    val narrator: String,
    val description: String,
    val year: String,
    val series: String,
    val totalDurationMs: Long,
    val totalFileSize: Long,
    val coverPath: String?,
    val thumbnailPath: String?,
    val lastScannedAt: Long,
    val addedAt: Long,
    // Read Status Type Safe: Use ReadStatus enum for type safety.
    val readStatus: AudiobookSchema.ReadStatus,
    val progressPercent: Int,
    val lastPlayedAt: Long,
    val isFinished: Boolean,
    val isInProgress: Boolean,
    val isNotStarted: Boolean
)

/**
 * Home Catalog Snapshot (Application-owned catalog organization result)
 * Carries the filtered, sorted, grouped, and recent Home collections after applying catalog preferences, keeping ordering rules out of the UI ViewModel.
 */
data class HomeCatalogSnapshot(
    val audiobooks: List<HomeBookItem> = emptyList(),
    val hasRegisteredLibraryRoots: Boolean = false,
    val selectedFilter: HomeFilter = HomeFilter.NotStarted,
    val homeBookStatusFilter: HomeBookStatusFilter = HomeBookStatusFilter.All,
    val filteredAudiobooks: List<HomeBookItem> = emptyList(),
    val groupedAudiobooks: Map<String, List<HomeBookItem>> = emptyMap(),
    val recentBooks: List<HomeBookItem> = emptyList(),
    val shouldShowRecentBooks: Boolean = false,
    val homeSortRule: HomeSortRule = HomeSortRule.Author,
    val homeSortDirection: HomeSortDirection = HomeSortDirection.Ascending
)

// Map HomeBookItem to DetailBookItem (Abstract boundary mapping from UI layer to application layer)
// This encapsulates the conversion logic of book projections, allowing it to be easily unit-tested and keeping Composables focused on pure rendering.
fun HomeBookItem.toDetailBookItem(): DetailBookItem {
    return DetailBookItem(
        id = id,
        rootId = rootId,
        sourceType = sourceType,
        title = title,
        author = author,
        narrator = narrator,
        description = description,
        year = year,
        totalDurationMs = totalDurationMs,
        totalFileSize = totalFileSize,
        coverPath = coverPath,
        thumbnailPath = thumbnailPath,
        lastScannedAt = lastScannedAt,
        progressPercent = progressPercent,
        readStatus = readStatus
    )
}

/**
 * Home Library Read Model (Scene-level catalog surface for the home screen)
 * Exposes only the audiobook stream needed to derive the home catalog while hiding the full library gateway bus.
 */
interface HomeLibraryReadModel {
    val audiobooks: Flow<List<HomeBookItem>>

    /**
     * Registered Library Presence Stream (Home-level media source availability)
     * Exposes a boolean root-presence signal so Home can distinguish an unconfigured app from an empty scanned catalog without depending on SettingsRootItem or persistence entities.
     */
    val hasRegisteredLibraryRoots: Flow<Boolean>

    /**
     * Organized Home Catalog Stream (Application-owned filtering and ordering)
     * Accepts transient UI overrides plus persisted settings, then emits the complete catalog shape that Home can render without sorting in the ViewModel.
     */
    fun observeCatalog(
        userFilterSelections: Flow<HomeFilter?>,
        userBookStatusSelections: Flow<HomeBookStatusFilter?>,
        settings: Flow<AppSettings>
    ): Flow<HomeCatalogSnapshot>
}

/**
 * Home Library Use Cases (Scene-level commands owned by the home screen)
 * Groups home catalog commands without exposing settings, playback, subtitle, or detail-editing library capabilities.
 */
interface HomeLibraryUseCases {
    fun scheduleColdStartSync()

    fun scheduleUserSync()

    fun addLocalRootAndScheduleSync(uri: Uri)

    // Update Read Status: Update readStatus parameter type to ReadStatus enum.
    suspend fun updateReadStatus(bookId: String, readStatus: AudiobookSchema.ReadStatus)

    suspend fun regenerateCoverAndMetadata(bookId: String)

    suspend fun clearSearchHistory()
}

/**
 * Default Home Library Read Model (Adapter from granular library gateways to the home scene interface)
 * Keeps Home callers off the broad transition facade while the composition root still owns it for other screens.
 */
class DefaultHomeLibraryReadModel(
    private val bookCatalogGateway: BookCatalogGateway,
    private val libraryRootGateway: LibraryRootGateway
) : HomeLibraryReadModel {
    override val audiobooks: Flow<List<HomeBookItem>>
        get() = bookCatalogGateway.audiobooks.map { books ->
            // Home Catalog Projection Mapping (Keep Room relationship rows behind the home adapter)
            // Home UI needs only renderable catalog fields and progress flags, so the adapter strips persistence wrappers here.
            books.map { it.toHomeBookItem() }
        }

    override val hasRegisteredLibraryRoots: Flow<Boolean>
        get() = libraryRootGateway.observeLibraryRoots().map { roots ->
            // Home Library Root Presence Projection (Expose only whether any media source has been registered)
            // The Home scene needs this boolean to decide if the onboarding FAB should remain visible without importing settings rows or Room root entities.
            roots.isNotEmpty()
        }

    override fun observeCatalog(
        userFilterSelections: Flow<HomeFilter?>,
        userBookStatusSelections: Flow<HomeBookStatusFilter?>,
        settings: Flow<AppSettings>
    ): Flow<HomeCatalogSnapshot> {
        return combine(
            audiobooks,
            hasRegisteredLibraryRoots,
            userFilterSelections,
            userBookStatusSelections,
            settings
        ) { audiobooks, hasRegisteredLibraryRoots, userSelection, userBookStatusSelection, appSettings ->
            HomeCatalogOrganizer.build(
                audiobooks = audiobooks,
                hasRegisteredLibraryRoots = hasRegisteredLibraryRoots,
                userSelection = userSelection,
                userBookStatusSelection = userBookStatusSelection,
                appSettings = appSettings
            )
        }.flowOn(Dispatchers.Default)
    }
}

/**
 * Home Book Status Matching (Maps the UI-facing availability filter onto the data-layer book status)
 * Keeps the schema dependency in the application layer so the shared HomeBookStatusFilter enum stays free of data-layer types.
 */
private fun HomeBookStatusFilter.matches(bookStatus: AudiobookSchema.BookStatus): Boolean =
    when (this) {
        HomeBookStatusFilter.All -> true
        HomeBookStatusFilter.Ready -> bookStatus == AudiobookSchema.BookStatus.READY
        HomeBookStatusFilter.Partial -> bookStatus == AudiobookSchema.BookStatus.PARTIAL
        HomeBookStatusFilter.Unavailable -> bookStatus == AudiobookSchema.BookStatus.UNAVAILABLE
    }

/**
 * Home Catalog Organizer (Applies catalog-domain visibility and ordering rules)
 * Keeps read-progress filtering, availability filtering, script-cluster sorting, grouping, and recent slices beside the Home read model instead of the UI ViewModel.
 */
private object HomeCatalogOrganizer {
    fun build(
        audiobooks: List<HomeBookItem>,
        hasRegisteredLibraryRoots: Boolean,
        userSelection: HomeFilter?,
        userBookStatusSelection: HomeBookStatusFilter?,
        appSettings: AppSettings
    ): HomeCatalogSnapshot {
        val activeFilter = userSelection ?: appSettings.homeFilter
        val activeBookStatusFilter = userBookStatusSelection ?: appSettings.homeBookStatusFilter
        val statusFilteredAudiobooks = audiobooks.filter { book ->
            activeBookStatusFilter.matches(book.status)
        }
        val filteredAudiobooks = statusFilteredAudiobooks.filter { book ->
            book.matchesFilter(activeFilter)
        }
        val sortedAudiobooks = HomeCatalogSortPolicy.sort(
            books = filteredAudiobooks,
            sortRule = appSettings.homeSortRule,
            sortDirection = appSettings.homeSortDirection
        )
        val groupedAudiobooks = sortedAudiobooks.groupBy { book ->
            HomeCatalogSortPolicy.groupLabel(book, appSettings.homeSortRule)
        }
        val recentBooks = when (activeFilter) {
            HomeFilter.NotStarted -> statusFilteredAudiobooks.filter { book -> book.isNotStarted }
                .sortedByDescending { book -> book.addedAt }
                .take(10)
            HomeFilter.InProgress -> statusFilteredAudiobooks.filter { book -> book.isInProgress && book.lastPlayedAt > 0 }
                .sortedByDescending { book -> book.lastPlayedAt }
                .take(5)
            HomeFilter.Finished -> emptyList()
        }

        return HomeCatalogSnapshot(
            audiobooks = audiobooks,
            hasRegisteredLibraryRoots = hasRegisteredLibraryRoots,
            selectedFilter = activeFilter,
            homeBookStatusFilter = activeBookStatusFilter,
            filteredAudiobooks = sortedAudiobooks,
            groupedAudiobooks = groupedAudiobooks,
            recentBooks = recentBooks,
            shouldShowRecentBooks = (
                activeFilter == HomeFilter.NotStarted ||
                    activeFilter == HomeFilter.InProgress
                ) && recentBooks.isNotEmpty(),
            homeSortRule = appSettings.homeSortRule,
            homeSortDirection = appSettings.homeSortDirection
        )
    }

    private fun HomeBookItem.matchesFilter(filter: HomeFilter): Boolean {
        return when (filter) {
            HomeFilter.NotStarted -> isNotStarted
            HomeFilter.InProgress -> isInProgress
            HomeFilter.Finished -> isFinished
        }
    }
}

/**
 * Default Home Library Use Cases (Adapter from granular gateways to home-scoped commands)
 * Centralizes trigger labels and gateway selection so LibraryViewModel does not learn the broader facade surface.
 */
class DefaultHomeLibraryUseCases(
    private val bookMetadataGateway: BookMetadataGateway,
    private val scanScheduler: ScanScheduler,
    private val libraryRootGateway: LibraryRootGateway,
    private val metadataRefreshGateway: MetadataRefreshGateway,
    private val searchHistoryGateway: SearchHistoryGateway
) : HomeLibraryUseCases {
    override fun scheduleColdStartSync() {
        scanScheduler.scheduleLibrarySync(COLD_START_TRIGGER)
    }

    override fun scheduleUserSync() {
        scanScheduler.scheduleLibrarySync(USER_TRIGGER)
    }

    override fun addLocalRootAndScheduleSync(uri: Uri) {
        libraryRootGateway.addLibraryRootAndScheduleSync(uri, USER_TRIGGER)
    }

    // Update Read Status: Update readStatus parameter type to ReadStatus enum.
    override suspend fun updateReadStatus(bookId: String, readStatus: AudiobookSchema.ReadStatus) {
        // Home Read Status Update (Use the semantic metadata seam for bookshelf state changes)
        // Home commands no longer depend on catalog search or file inventory just to change the user's reading status.
        bookMetadataGateway.updateBookReadStatus(bookId, readStatus)
    }

    override suspend fun regenerateCoverAndMetadata(bookId: String) {
        metadataRefreshGateway.forceRegenerateCoverAndMetadata(bookId)
    }

    override suspend fun clearSearchHistory() {
        searchHistoryGateway.clearHistory()
    }

    private companion object {
        private const val COLD_START_TRIGGER = "COLD_START"
        private const val USER_TRIGGER = "USER"
    }
}

/**
 * Home Book Projection Mapping (Translate BookWithProgress into the home scene item)
 * Localizes BookWithProgress knowledge inside the adapter while preserving home filter and sort semantics.
 */
private fun BookWithProgress.toHomeBookItem(): HomeBookItem {
    return HomeBookItem(
        id = book.id,
        rootId = book.rootId,
        sourceType = book.sourceType,
        status = book.status,
        title = book.title,
        author = book.author,
        narrator = book.narrator,
        description = book.description,
        year = book.year,
        series = book.series,
        totalDurationMs = book.totalDurationMs,
        totalFileSize = book.totalFileSize,
        coverPath = book.coverPath,
        thumbnailPath = book.thumbnailPath,
        lastScannedAt = book.lastScannedAt,
        addedAt = book.addedAt,
        readStatus = book.readStatus,
        progressPercent = progressPercent,
        lastPlayedAt = progress?.lastPlayedAt ?: 0L,
        isFinished = isFinished,
        isInProgress = isInProgress,
        isNotStarted = isNotStarted
    )
}
