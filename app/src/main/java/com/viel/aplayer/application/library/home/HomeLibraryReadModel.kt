package com.viel.aplayer.application.library.home

import android.net.Uri
import com.viel.aplayer.application.library.LibraryBookSourceType
import com.viel.aplayer.application.library.LibraryBookStatus
import com.viel.aplayer.application.library.LibraryReadStatus
import com.viel.aplayer.application.library.detail.DetailBookItem
import com.viel.aplayer.application.library.toLibraryBookSourceType
import com.viel.aplayer.application.library.toLibraryBookStatus
import com.viel.aplayer.application.library.toLibraryReadStatus
import com.viel.aplayer.application.library.toSchemaReadStatus
import com.viel.aplayer.data.book.BookCatalogGateway
import com.viel.aplayer.data.book.BookMetadataGateway
import com.viel.aplayer.data.book.HomeCatalogRow
import com.viel.aplayer.data.cover.CoverRecoveryGateway
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.metadata.MetadataRefreshGateway
import com.viel.aplayer.data.root.LibraryRootGateway
import com.viel.aplayer.data.scan.ScanScheduler
import com.viel.aplayer.data.search.SearchHistoryGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-free catalog projection for the bookshelf.
 * Carries the metadata, progress, and application-level status fields rendered or sorted by Home without exposing Room relationship rows or schema enums.
 */
data class HomeBookItem(
    val id: String,
    val rootId: String,

    val sourceType: LibraryBookSourceType,

    val status: LibraryBookStatus,
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

    val readStatus: LibraryReadStatus,
    val progressPercent: Int,
    val lastPlayedAt: Long,
    val isFinished: Boolean,
    val isInProgress: Boolean,
    val isNotStarted: Boolean
)

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
 * Scene-level catalog surface for the home screen.
 * Exposes raw Home projections and root presence while keeping page-level catalog organization inside the Home ViewModel.
 */
interface HomeLibraryReadModel {
    val audiobooks: Flow<List<HomeBookItem>>

    /**
     * Home-level media source availability.
     * Exposes a boolean root-presence signal so Home can distinguish an unconfigured app from an empty scanned catalog without depending on SettingsRootItem or persistence entities.
     */
    val hasRegisteredLibraryRoots: Flow<Boolean>

}

/**
 * Scene-level commands owned by the home screen.
 * Groups home catalog commands without exposing settings, playback, subtitle, or detail-editing library capabilities.
 */
interface HomeLibraryUseCases {
    fun scheduleColdStartSync()

    fun addLocalRootAndScheduleSync(uri: Uri)


    suspend fun updateReadStatus(bookId: String, readStatus: LibraryReadStatus)

    suspend fun regenerateCoverAndMetadata(bookId: String)

    /**
     * Deferred home-start cover self-heal sweep.
     * Home triggers this shortly after first paint so the catalog stream no longer probes covers inline on every emission.
     */
    suspend fun recoverMissingCovers()

    suspend fun clearSearchHistory()
}

/**
 * Adapter from granular library gateways to the home scene interface.
 * Keeps Home callers off the broad transition facade while the composition root still owns it for other screens.
 */
class DefaultHomeLibraryReadModel(
    private val bookCatalogGateway: BookCatalogGateway,
    private val libraryRootGateway: LibraryRootGateway
) : HomeLibraryReadModel {
    override val audiobooks: Flow<List<HomeBookItem>>
        get() = bookCatalogGateway.homeCatalogRows.map { books ->
            books.map { it.toHomeBookItem() }
        }

    override val hasRegisteredLibraryRoots: Flow<Boolean>
        get() = libraryRootGateway.observeLibraryRoots().map { roots ->
            roots.isNotEmpty()
        }
}

/**
 * Adapter from granular gateways to home-scoped commands.
 * Centralizes trigger labels and gateway selection so LibraryViewModel does not learn the broader facade surface.
 */
class DefaultHomeLibraryUseCases(
    private val bookMetadataGateway: BookMetadataGateway,
    private val scanScheduler: ScanScheduler,
    private val libraryRootGateway: LibraryRootGateway,
    private val metadataRefreshGateway: MetadataRefreshGateway,
    private val searchHistoryGateway: SearchHistoryGateway,
    private val coverRecoveryGateway: CoverRecoveryGateway
) : HomeLibraryUseCases {
    override fun scheduleColdStartSync() {
        scanScheduler.scheduleLibrarySync(COLD_START_TRIGGER)
    }

    override fun addLocalRootAndScheduleSync(uri: Uri) {
        libraryRootGateway.addLibraryRootAndScheduleSync(uri, USER_TRIGGER)
    }


    override suspend fun updateReadStatus(bookId: String, readStatus: LibraryReadStatus) {
        bookMetadataGateway.updateBookReadStatus(bookId, readStatus.toSchemaReadStatus())
    }

    override suspend fun regenerateCoverAndMetadata(bookId: String) {
        metadataRefreshGateway.forceRegenerateCoverAndMetadata(bookId)
    }

    override suspend fun recoverMissingCovers() {
        coverRecoveryGateway.recoverMissingCovers()
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
 * Translate the lightweight catalog row into the home scene item.
 * Localizes data-layer projection knowledge inside the adapter while preserving every raw field the Home ViewModel needs for filtering, sorting, grouping, and recents.
 */
private fun HomeCatalogRow.toHomeBookItem(): HomeBookItem {
    return HomeBookItem(
        id = id,
        rootId = rootId,
        sourceType = sourceType.toLibraryBookSourceType(),
        status = status.toLibraryBookStatus(),
        title = title,
        author = author,
        narrator = narrator,
        description = "",
        year = year,
        series = series,
        totalDurationMs = totalDurationMs,
        totalFileSize = totalFileSize,
        coverPath = coverPath,
        thumbnailPath = thumbnailPath,
        lastScannedAt = lastScannedAt,
        addedAt = addedAt,
        readStatus = readStatus.toLibraryReadStatus(),
        progressPercent = progressPercent,
        lastPlayedAt = lastPlayedAt,
        isFinished = readStatus == AudiobookSchema.ReadStatus.FINISHED,
        isInProgress = readStatus == AudiobookSchema.ReadStatus.IN_PROGRESS,
        isNotStarted = readStatus == AudiobookSchema.ReadStatus.NOT_STARTED
    )
}
