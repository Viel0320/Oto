package com.viel.aplayer.library.readmodel

import android.net.Uri
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.gateway.BookQueryGateway
import com.viel.aplayer.data.gateway.LibraryRootGateway
import com.viel.aplayer.data.gateway.MetadataRefreshGateway
import com.viel.aplayer.data.gateway.ScanScheduler
import com.viel.aplayer.data.gateway.SearchHistoryGateway
import kotlinx.coroutines.flow.Flow

/**
 * Home Library Read Model (Scene-level catalog surface for the home screen)
 * Exposes only the audiobook stream needed to derive the home catalog while hiding the full library gateway bus.
 */
interface HomeLibraryReadModel {
    val audiobooks: Flow<List<BookWithProgress>>
}

/**
 * Home Library Use Cases (Scene-level commands owned by the home screen)
 * Groups home catalog commands without exposing settings, playback, subtitle, or detail-editing library capabilities.
 */
interface HomeLibraryUseCases {
    fun scheduleColdStartSync()

    fun scheduleUserSync()

    fun addLocalRootAndScheduleSync(uri: Uri)

    suspend fun updateReadStatus(bookId: String, readStatus: String)

    suspend fun regenerateCoverAndMetadata(bookId: String)

    suspend fun clearSearchHistory()
}

/**
 * Default Home Library Read Model (Adapter from granular library gateways to the home scene interface)
 * Keeps Home callers off the broad transition facade while the composition root still owns it for other screens.
 */
class DefaultHomeLibraryReadModel(
    private val bookQueryGateway: BookQueryGateway
) : HomeLibraryReadModel {
    override val audiobooks: Flow<List<BookWithProgress>>
        get() = bookQueryGateway.audiobooks
}

/**
 * Default Home Library Use Cases (Adapter from granular gateways to home-scoped commands)
 * Centralizes trigger labels and gateway selection so LibraryViewModel does not learn the broader facade surface.
 */
class DefaultHomeLibraryUseCases(
    private val bookQueryGateway: BookQueryGateway,
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

    override suspend fun updateReadStatus(bookId: String, readStatus: String) {
        bookQueryGateway.updateBookReadStatus(bookId, readStatus)
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
