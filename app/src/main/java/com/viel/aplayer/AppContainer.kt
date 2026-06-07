package com.viel.aplayer

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.abs.net.RealAbsApiClient
import com.viel.aplayer.abs.playback.AbsPlaybackCredentialResolver
import com.viel.aplayer.abs.playback.AbsPlaybackSessionSyncer
import com.viel.aplayer.abs.playback.AbsProgressConflictCoordinator
import com.viel.aplayer.abs.sync.AbsCatalogSynchronizer
import com.viel.aplayer.abs.sync.AbsCoverCache
import com.viel.aplayer.abs.sync.AbsSyncTaskCoordinator
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.LibraryFacade
import com.viel.aplayer.data.cache.CacheEvictionCoordinator
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.ChapterWithBookFile
import com.viel.aplayer.data.gateway.BookQueryGateway
import com.viel.aplayer.data.gateway.CoverGateway
import com.viel.aplayer.data.gateway.CoverUriResolver
import com.viel.aplayer.data.gateway.LibraryRootGateway
import com.viel.aplayer.data.gateway.ProgressGateway
import com.viel.aplayer.data.gateway.ScanScheduler
import com.viel.aplayer.data.gateway.SearchHistoryGateway
import com.viel.aplayer.data.service.AndroidCoverUriResolver
import com.viel.aplayer.data.service.BookQueryService
import com.viel.aplayer.data.service.CoverService
import com.viel.aplayer.data.service.LibraryRootService
import com.viel.aplayer.data.service.ProgressService
import com.viel.aplayer.data.service.ScanService
import com.viel.aplayer.data.service.SearchService
import com.viel.aplayer.domain.usecase.DeleteBookUseCase
import com.viel.aplayer.domain.usecase.DeleteLibraryRootUseCase
import com.viel.aplayer.library.availability.AvailabilityChecker
import com.viel.aplayer.library.availability.DetailAvailabilityChecker
import com.viel.aplayer.library.availability.PlaybackReachabilityManager
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.library.vfs.cache.RoomDirectoryListingCache
import com.viel.aplayer.library.vfs.cache.VfsRangeCache
import com.viel.aplayer.media.PlaybackFileLookup
import com.viel.aplayer.media.PlaybackSourcePreflight
import com.viel.aplayer.media.parser.CoverExtractor
import com.viel.aplayer.media.parser.CoverRecoveryHelper
import com.viel.aplayer.media.parser.MetadataResolver
import com.viel.aplayer.media.subtitle.SubtitleFileResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Global Dependency Container (Manages lifecycle and instantiation of core gateway services)
 * Serves as a lightweight DI provider for unified coordination across application domains.
 */
interface AppContainer : java.io.Closeable {
    val settingsRepository: AppSettingsRepository
    
    /**
     * Cross-Domain Use Case (Unregister and purge library root coordinates)
     * Coordinates active playback teardown and background physical data cleanup.
     */
    val deleteLibraryRootUseCase: DeleteLibraryRootUseCase

    /**
     * Delete Book Use Case (Delete audiobook, chapters, bookmarks, and physical cache files)
     */
    val deleteBookUseCase: DeleteBookUseCase

    /**
     * High-Level Library Facade (Unified entry point aggregating core domain services)
     * Simplifies routing to book query, progress database syncing, and scanning sub-systems.
     */
    val libraryFacade: LibraryFacade

    /**
     * Book Query Gateway (Read-write router for audiobook queries and bookmarks)
     */
    val bookQueryGateway: BookQueryGateway

    /**
     * Progress Sync Gateway (State router for playback position and audio file status)
     */
    val progressGateway: ProgressGateway

    /**
     * Media Scanner Gateway (Scheduler interface for triggering library updates)
     */
    val scanScheduler: ScanScheduler

    /**
     * Library Root Gateway (Maintenance interface for directory registrations)
     */
    val libraryRootGateway: LibraryRootGateway

    /**
     * Cover Metadata Gateway (Domain router for image extraction and backdrop color caching)
     */
    val coverGateway: CoverGateway

    /**
     * Search History Gateway (Persistence management for keyword lookup logs)
     */
    val searchHistoryGateway: SearchHistoryGateway

    /**
     * Virtual File System Interface (Single I/O access point for file path abstractions)
     */
    val vfsFileInterface: VfsFileInterface

    /**
     * Playback File Resolver (Lookup utility to associate media IDs with physical files)
     */
    val playbackFileLookup: PlaybackFileLookup

    /**
     * Playback Source Preflight (DB-only root lifecycle gate before media source creation)
     * Reads persisted library root rows without opening files or probing remote endpoints.
     */
    val playbackSourcePreflight: PlaybackSourcePreflight

    /**
     * Playback Manager Instance (Singleton registry for media controller coordination)
     * Promotes decoupled architecture and simplifies component isolation during unit testing.
     */
    val playbackManager: com.viel.aplayer.media.PlaybackManager

    /**
     * Search History Storage (Singleton reference to search term DataStore backend)
     */
    val searchHistoryStore: com.viel.aplayer.data.store.SearchHistoryStore

    /**
     * Auto Rewind Controller (Singleton manager tracking playback progress self-healing)
     */
    val autoRewindManager: com.viel.aplayer.media.AutoRewindManager

    /**
     * ABS Catalog Synchronizer (Dedicated mirror processor for Audiobookshelf servers)
     * Kept separate from LibraryFacade to prevent remote REST details leaking into the local domain.
     */
    val absCatalogSynchronizer: AbsCatalogSynchronizer

    /**
     * ABS Playback Session Syncer (Coordinator for remote server progress handshakes)
     * Restricts operations to play/sync/close events, keeping local database records as the source of truth.
     */
    val absPlaybackSessionSyncer: AbsPlaybackSessionSyncer

    /**
     * ABS Progress Conflict Coordinator (Remote/local progress arbitration service)
     * Provides playback prompts and upload guards without leaking ABS protocol details into generic progress services.
     */
    val absProgressConflictCoordinator: AbsProgressConflictCoordinator

    /**
     * Application-Level ABS Sync Coordinator (Coordinates background server synchronization)
     * Ensures sync events persist beyond SettingsActivity lifecycle, preventing unexpected interruptions.
     */
    val absSyncTaskCoordinator: AbsSyncTaskCoordinator
}

@UnstableApi
internal class CoreContainer(val context: Context) {
    val database: AppDatabase by lazy {
        // Database Initialization: Instantiates the single room database coordinator safely.
        AppDatabase.getInstance(context)
    }

    val playbackManager: com.viel.aplayer.media.PlaybackManager by lazy {
        // Playback Singleton Initialization: Connects media lifecycle events across system interfaces.
        com.viel.aplayer.media.PlaybackManager.getInstance(context)
    }

    val searchHistoryStore: com.viel.aplayer.data.store.SearchHistoryStore by lazy {
        // Search Store Initialization: Prepares the settings lookup and search logs DataStore.
        com.viel.aplayer.data.store.SearchHistoryStore.getInstance(context)
    }

    val autoRewindManager: com.viel.aplayer.media.AutoRewindManager by lazy {
        // Rewind Manager Initialization: Orchestrates the self-healing progress tracking logic.
        com.viel.aplayer.media.AutoRewindManager.getInstance(context)
    }

    val settingsRepository: AppSettingsRepository by lazy {
        // App Settings Repository: Accesses the application configuration options.
        AppSettingsRepository.getInstance(context)
    }
}

@UnstableApi
internal class VfsContainer(
    val context: Context,
    val core: CoreContainer
) {
    val vfsRangeCache by lazy {
        // Range Cache Initialization: Prepares a dedicated cache registry for metadata extraction blocks.
        VfsRangeCache(context.applicationContext)
    }

    val directoryListingCache by lazy {
        // Directory Listing Cache: Cache for directory child folders to optimize scan latency.
        RoomDirectoryListingCache(
            directoryChildCacheDao = core.database.directoryChildCacheDao()
        )
    }

    val vfsFileInterface: VfsFileInterface by lazy {
        // Vfs File Interface: Interconnects local files and remote directories with unified VFS logic.
        VfsFileInterface(
            context.applicationContext,
            libraryRootDao = core.database.libraryRootDao(),
            rangeCache = vfsRangeCache
        )
    }

    val playbackFileLookup: PlaybackFileLookup by lazy {
        // Playback File Lookup: Associates book identifiers to their respective audio files.
        com.viel.aplayer.media.DefaultPlaybackFileLookup(
            core.database.bookDao()
        )
    }

    val playbackSourcePreflight: PlaybackSourcePreflight by lazy {
        // Playback Preflight Guard: Evaluates book root availability before constructing media sources.
        PlaybackSourcePreflight(core.database.libraryRootDao())
    }
}

@UnstableApi
internal class LocalLibraryContainer(
    val context: Context,
    val core: CoreContainer,
    val vfs: VfsContainer
) : java.io.Closeable {
    val coverExtractor: CoverExtractor by lazy {
        // Cover Extractor: Parses image headers to extract embedded cover artwork files.
        CoverExtractor(context.applicationContext)
    }

    val metadataResolver: MetadataResolver by lazy {
        // Metadata Resolver: Extracts tag structures and timeline configurations from media files.
        MetadataResolver(vfs.vfsFileInterface)
    }

    val detailAvailabilityChecker: DetailAvailabilityChecker by lazy {
        // Detail Checker: Performs robust connection checks for books in detail displays.
        DetailAvailabilityChecker(context.applicationContext)
    }

    val availabilityChecker: AvailabilityChecker by lazy {
        // Availability Checker: Evaluates if file handles are reachable before launching players.
        AvailabilityChecker(context.applicationContext)
    }

    val subtitleFileResolver: SubtitleFileResolver by lazy {
        // Subtitle Resolver: Searches local directories to resolve matching lyrics and captions.
        SubtitleFileResolver(
            context = context.applicationContext,
            bookDao = core.database.bookDao(),
            fileReader = vfs.vfsFileInterface
        )
    }

    val playbackReachabilityManager: PlaybackReachabilityManager by lazy {
        // Reachability Monitor: Verifies accessibility of next files during play lists loop.
        PlaybackReachabilityManager(
            context,
            core.database.bookDao(),
            core.database.libraryRootDao()
        )
    }

    val coverUriResolver: CoverUriResolver by lazy {
        // Cover Uri Resolver: Bridges raw filesystem paths to application provider URIs.
        AndroidCoverUriResolver(context.applicationContext)
    }

    var coverRecoveryScope: CoroutineScope? = null
        private set

    val coverRecoveryHelper: CoverRecoveryHelper by lazy {
        // Recovery Scope Allocation: Instantiates supervisor job for background cover recovery.
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        coverRecoveryScope = s
        CoverRecoveryHelper(
            context = context.applicationContext,
            bookDao = core.database.bookDao(),
            libraryRootDao = core.database.libraryRootDao(),
            coverExtractor = coverExtractor,
            scope = s,
            fileReader = vfs.vfsFileInterface
        )
    }

    val bookQueryGateway: BookQueryGateway by lazy {
        // Book Query Service: Interconnects library UI workflows to the room backend.
        BookQueryService(
            coverUriResolver = coverUriResolver,
            bookDao = core.database.bookDao(),
            chapterDao = core.database.chapterDao(),
            bookmarkDao = core.database.bookmarkDao(),
            scanSessionDao = core.database.scanSessionDao(),
            coverRecoveryHelper = coverRecoveryHelper
        )
    }

    val progressGateway: ProgressGateway by lazy {
        // Progress Sync Service: Stores listening checkpoints into the local sqlite instance.
        ProgressService(
            bookDao = core.database.bookDao(),
            reachabilityManager = playbackReachabilityManager
        )
    }

    val scanScheduler: ScanScheduler by lazy {
        // Media Scanner Service: Runs directory crawl passes to locate added/deleted books.
        ScanService(
            context = context,
            coverRecoveryHelper = coverRecoveryHelper,
            vfsFileInterface = vfs.vfsFileInterface,
            directoryListingCache = vfs.directoryListingCache,
            playbackManager = core.playbackManager
        )
    }

    val cacheEvictionCoordinator by lazy {
        // Cache Evictor: Deletes redundant cached artwork and folders during library resets.
        CacheEvictionCoordinator(
            context = context.applicationContext,
            bookDao = core.database.bookDao(),
            directoryCacheDao = core.database.directoryCacheDao(),
            directoryChildCacheDao = core.database.directoryChildCacheDao(),
            vfsRangeCache = vfs.vfsRangeCache
        )
    }

    val libraryRootGateway: LibraryRootGateway by lazy {
        // Library Root Gateway: Manages directory registrations and coordinates folder purging.
        LibraryRootService(
            context = context,
            libraryRootDao = core.database.libraryRootDao(),
            bookDao = core.database.bookDao(),
            scanScheduler = scanScheduler,
            cacheEvictionCoordinator = cacheEvictionCoordinator
        )
    }

    val coverGateway: CoverGateway by lazy {
        // Cover Gateway Service: Handles batch image extractions and metadata queries.
        CoverService(
            bookDao = core.database.bookDao(),
            chapterDao = core.database.chapterDao(),
            coverRecoveryHelper = coverRecoveryHelper,
            coverExtractor = coverExtractor,
            metadataResolver = metadataResolver,
            subtitleResolver = subtitleFileResolver,
            detailAvailabilityChecker = detailAvailabilityChecker,
            availabilityChecker = availabilityChecker,
            database = core.database
        )
    }

    val searchHistoryGateway: SearchHistoryGateway by lazy {
        // Search Service Gateway: Persists history terms to data store profiles.
        SearchService(
            searchHistoryStore = core.searchHistoryStore
        )
    }

    val libraryFacade: LibraryFacade by lazy {
        // Library Domain Facade: Combines the query, scanner, roots, progress, and cover adapters.
        LibraryFacade(
            bookQueryGateway = bookQueryGateway,
            progressGateway = progressGateway,
            scanScheduler = scanScheduler,
            libraryRootGateway = libraryRootGateway,
            coverGateway = coverGateway,
            searchHistoryGateway = searchHistoryGateway
        )
    }

    val deleteLibraryRootUseCase: DeleteLibraryRootUseCase by lazy {
        // Library Deletion Use Case: Teardown logic for removing library roots.
        DeleteLibraryRootUseCase(
            playbackManager = core.playbackManager,
            bookQueryGateway = bookQueryGateway,
            libraryRootGateway = libraryRootGateway
        )
    }

    val deleteBookUseCase: DeleteBookUseCase by lazy {
        // Delete Book Use Case: Removes library references and covers of a target audiobook.
        DeleteBookUseCase(
            playbackManager = core.playbackManager,
            libraryFacade = libraryFacade
        )
    }

    override fun close() {
        // Recovery Scope Cancel: Cancels the recovery coroutine context safely to avoid runtime memory leaks.
        coverRecoveryScope?.let { s ->
            runCatching { s.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
        }
        // Gateway Disposal: Triggers release logic across closeable local library gateways.
        (bookQueryGateway as? java.io.Closeable)?.close()
        (progressGateway as? java.io.Closeable)?.close()
        (scanScheduler as? java.io.Closeable)?.close()
        (libraryRootGateway as? java.io.Closeable)?.close()
        (coverGateway as? java.io.Closeable)?.close()
        (searchHistoryGateway as? java.io.Closeable)?.close()
    }
}

@UnstableApi
internal class AbsContainer(
    val context: Context,
    val core: CoreContainer,
    val vfs: VfsContainer,
    val local: LocalLibraryContainer
) : java.io.Closeable {
    val absCredentialStore by lazy {
        // ABS Credential Store: Securely manages credentials for Audiobookshelf.
        AbsCredentialStore.getInstance(context.applicationContext)
    }

    val absApiClient by lazy {
        // ABS Client Client: Sends REST calls to Audiobookshelf endpoints.
        RealAbsApiClient(appSettingsRepository = core.settingsRepository)
    }

    val absCoverCache by lazy {
        // ABS Cover Cache: Caches downloaded covers from Audiobookshelf servers locally.
        AbsCoverCache(context.applicationContext)
    }

    val absPlaybackCredentialResolver by lazy {
        // ABS Credential Resolver: Resolves credential details for remote playback.
        AbsPlaybackCredentialResolver(
            libraryRootDao = core.database.libraryRootDao(),
            credentialStore = absCredentialStore
        )
    }

    val absCatalogSynchronizer: AbsCatalogSynchronizer by lazy {
        // ABS Catalog Synchronizer: Mirrors server library catalogs to local room tables.
        AbsCatalogSynchronizer(
            apiClient = absApiClient,
            credentialStore = absCredentialStore,
            catalogStore = core.database.absCatalogDao(),
            coverCache = absCoverCache
        )
    }

    val absProgressConflictCoordinator: AbsProgressConflictCoordinator by lazy {
        // Progress Conflict Coordinator: Arbitrates conflicting play timestamps.
        AbsProgressConflictCoordinator(
            apiClient = absApiClient,
            bookQueryGateway = local.bookQueryGateway,
            progressGateway = local.progressGateway,
            credentialProvider = { book -> absPlaybackCredentialResolver.resolve(book) }
        )
    }

    val absPlaybackSessionSyncer: AbsPlaybackSessionSyncer by lazy {
        // Playback Session Syncer: Submits media play progress intervals to servers.
        AbsPlaybackSessionSyncer(
            apiClient = absApiClient,
            absPlaybackSessionDao = core.database.absPlaybackSessionDao(),
            absPendingProgressSyncDao = core.database.absPendingProgressSyncDao(),
            catalogStore = core.database.absCatalogDao(),
            credentialProvider = { book -> absPlaybackCredentialResolver.resolve(book) },
            progressConflictCoordinator = absProgressConflictCoordinator
        )
    }

    val absSyncTaskCoordinator: AbsSyncTaskCoordinator by lazy {
        // Sync Task Coordinator: Coordinates background Audiobookshelf catalog runs.
        AbsSyncTaskCoordinator(
            libraryRootDao = core.database.libraryRootDao(),
            synchronizer = absCatalogSynchronizer,
            playbackManager = core.playbackManager,
            rootPreflight = local.libraryRootGateway::refreshLibraryRootStatus
        )
    }

    override fun close() {
        // ABS Resources Disposal: Releases remote coordination modules and sync tasks safely.
        absSyncTaskCoordinator.close()
    }
}

@UnstableApi
class DefaultAppContainer(private val context: Context) : AppContainer {

    internal val core = CoreContainer(context)
    internal val vfs = VfsContainer(context, core)
    internal val local = LocalLibraryContainer(context, core, vfs)
    internal val abs = AbsContainer(context, core, vfs, local)

    override val settingsRepository: AppSettingsRepository
        get() = core.settingsRepository

    override val deleteLibraryRootUseCase: DeleteLibraryRootUseCase
        get() = local.deleteLibraryRootUseCase

    override val deleteBookUseCase: DeleteBookUseCase
        get() = local.deleteBookUseCase

    override val libraryFacade: LibraryFacade
        get() = local.libraryFacade

    override val bookQueryGateway: BookQueryGateway
        get() = local.bookQueryGateway

    override val progressGateway: ProgressGateway
        get() = local.progressGateway

    override val scanScheduler: ScanScheduler
        get() = local.scanScheduler

    override val libraryRootGateway: LibraryRootGateway
        get() = local.libraryRootGateway

    override val coverGateway: CoverGateway
        get() = local.coverGateway

    override val searchHistoryGateway: SearchHistoryGateway
        get() = local.searchHistoryGateway

    override val vfsFileInterface: VfsFileInterface
        get() = vfs.vfsFileInterface

    override val playbackFileLookup: PlaybackFileLookup
        get() = vfs.playbackFileLookup

    override val playbackSourcePreflight: PlaybackSourcePreflight
        get() = vfs.playbackSourcePreflight

    override val playbackManager: com.viel.aplayer.media.PlaybackManager
        get() = core.playbackManager

    override val searchHistoryStore: com.viel.aplayer.data.store.SearchHistoryStore
        get() = core.searchHistoryStore

    override val autoRewindManager: com.viel.aplayer.media.AutoRewindManager
        get() = core.autoRewindManager

    override val absCatalogSynchronizer: AbsCatalogSynchronizer
        get() = abs.absCatalogSynchronizer

    override val absPlaybackSessionSyncer: AbsPlaybackSessionSyncer
        get() = abs.absPlaybackSessionSyncer

    override val absProgressConflictCoordinator: AbsProgressConflictCoordinator
        get() = abs.absProgressConflictCoordinator

    override val absSyncTaskCoordinator: AbsSyncTaskCoordinator
        get() = abs.absSyncTaskCoordinator

    override fun close() {
        // Sub-container Teardown Delegation: Dispatches teardown operations directly to the respective domain containers.
        runCatching { local.close() }
        runCatching { abs.close() }
    }
}

/**
 * Unified Query-Time Chapters Projection Rules (Dynamic fallback mapping logic)
 * 
 * Rules:
 * 1. Preserves existing parsed database chapters; does not override authentic tags.
 * 2. Dynamically builds a single track fallback chapter if database chapter schemas are empty.
 * 3. Retains projection models strictly in-memory, avoiding persisting dummy entries to database tables.
 */
internal fun projectChaptersWithTrackFallback(
    book: BookEntity?,
    files: List<BookFileEntity>,
    chapters: List<ChapterWithBookFile>
): List<ChapterWithBookFile> {
    // Skip override: Returns database chapters directly to prevent altering real parsed content.
    if (chapters.isNotEmpty()) {
        return chapters
    }
    // Sanity reference checks: Avoids synthesizing chapters if the target book does not exist in cache records.
    if (book == null) {
        return chapters
    }
    // Files existence check: Requires valid audio track mappings to execute projection loops.
    val sortedFiles = files.sortedBy { file -> file.index }
    if (sortedFiles.isEmpty()) {
        return chapters
    }
    var runningStartMs = 0L
    return sortedFiles.mapIndexed { trackIndex, file ->
        val safeDurationMs = when {
            file.durationMs > 0L -> file.durationMs
            // Duration Fallback: Recovers duration from global book records if individual track duration is missing.
            sortedFiles.size == 1 && book.totalDurationMs > 0L -> book.totalDurationMs
            else -> 0L
        }.coerceAtLeast(0L)
        val chapter = ChapterEntity(
            // Stable Synthetic ID: Computes a name-based UUID using bookId and fileId parameters.
            // Prevents Compose UI list flickers and notification track updates from unstable random values.
            id = syntheticTrackProjectionChapterId(book.id, file.id),
            bookId = book.id,
            // Primary Key Bindings: Associates the dynamic chapter with the real BookFileEntity ID.
            // Ensures seek commands and chapters completion checks map correctly onto physical file entities.
            bookFileId = file.id,
            index = trackIndex,
            title = projectedTrackChapterTitle(book, file, trackIndex, sortedFiles.size),
            startPositionMs = runningStartMs,
            durationMs = safeDurationMs,
            fileOffsetMs = 0L,
            source = AudiobookSchema.ChapterSource.GENERATED
        )
        // Accumulative Timelines: Increases relative start offsets with accumulated durations.
        // Maps multi-file tracks onto unified timeline structures across local and remote sources.
        runningStartMs += safeDurationMs
        ChapterWithBookFile(
            chapter = chapter,
            bookFile = file
        )
    }
}

/**
 * Stable Synthetic ID Generation (In-memory UUID builder)
 * Generates a stable name-based UUID for virtual track-chapter projection schemas.
 * Isolates in-memory structures to prevent primary key pollution in persistent tables.
 */
internal fun syntheticTrackProjectionChapterId(bookId: String, fileId: String): String =
    UUID.nameUUIDFromBytes("track-projection:$bookId:$fileId".toByteArray(StandardCharsets.UTF_8)).toString()

/**
 * Resolve Dynamic Chapter Title (Label generation priorities)
 * Prioritizes the book's title for single-file track representations, and individual track display names for multi-file systems.
 */
internal fun projectedTrackChapterTitle(
    book: BookEntity,
    file: BookFileEntity,
    trackIndex: Int,
    totalTracks: Int
): String {
    val displayName = file.displayName.substringBeforeLast('.', file.displayName).ifBlank { file.displayName }
    return when {
        totalTracks == 1 && book.title.isNotBlank() -> book.title
        displayName.isNotBlank() -> displayName
        book.title.isNotBlank() -> "${book.title} ${trackIndex + 1}"
        else -> "Track ${trackIndex + 1}"
    }
}
