package com.viel.aplayer.graph

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.LibraryFacade
import com.viel.aplayer.data.cache.CacheEvictionCoordinator
import com.viel.aplayer.data.gateway.BookAvailabilityGateway
import com.viel.aplayer.data.gateway.BookQueryGateway
import com.viel.aplayer.data.gateway.CoverAssetGateway
import com.viel.aplayer.data.gateway.CoverUriResolver
import com.viel.aplayer.data.gateway.LibraryRootGateway
import com.viel.aplayer.data.gateway.MetadataRefreshGateway
import com.viel.aplayer.data.gateway.ProgressGateway
import com.viel.aplayer.data.gateway.ScanScheduler
import com.viel.aplayer.data.gateway.SearchHistoryGateway
import com.viel.aplayer.data.gateway.SubtitleGateway
import com.viel.aplayer.data.service.AndroidCoverUriResolver
import com.viel.aplayer.data.service.BookAvailabilityService
import com.viel.aplayer.data.service.BookQueryService
import com.viel.aplayer.data.service.CoverAssetService
import com.viel.aplayer.data.service.LibraryRootService
import com.viel.aplayer.data.service.MetadataRefreshService
import com.viel.aplayer.data.service.PlaybackPlanService
import com.viel.aplayer.data.service.ProgressService
import com.viel.aplayer.data.service.ScanService
import com.viel.aplayer.data.service.SearchService
import com.viel.aplayer.data.service.SubtitleService
import com.viel.aplayer.domain.usecase.BuildPlaybackPlanUseCase
import com.viel.aplayer.domain.usecase.DeleteBookUseCase
import com.viel.aplayer.domain.usecase.DeleteLibraryRootUseCase
import com.viel.aplayer.library.availability.AvailabilityChecker
import com.viel.aplayer.library.readmodel.DefaultHomeLibraryReadModel
import com.viel.aplayer.library.readmodel.DefaultHomeLibraryUseCases
import com.viel.aplayer.library.readmodel.HomeLibraryReadModel
import com.viel.aplayer.library.readmodel.HomeLibraryUseCases
import com.viel.aplayer.media.PlaybackPlanGateway
import com.viel.aplayer.media.parser.CoverExtractor
import com.viel.aplayer.media.parser.CoverRecoveryHelper
import com.viel.aplayer.media.parser.MetadataResolver
import com.viel.aplayer.media.subtitle.SubtitleFileResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Library Graph (Owns local library queries, scan scheduling, metadata, cover, and deletion use cases)
 * Gives UI and playback callers stable library-facing adapters while keeping implementation construction local.
 */
@UnstableApi
internal class LibraryGraph(
    val context: Context,
    val data: DataGraph,
    val media: MediaGraph,
    val uiEvents: UiEventGraph
) : java.io.Closeable {
    val coverExtractor: CoverExtractor by lazy {
        // Cover Extractor: Parses image headers to extract embedded cover artwork files.
        CoverExtractor(context.applicationContext)
    }

    val metadataResolver: MetadataResolver by lazy {
        // Metadata Resolver: Extracts tag structures and timeline configurations from media files.
        MetadataResolver(media.vfsFileInterface)
    }

    val availabilityChecker: AvailabilityChecker by lazy {
        // Availability Checker: Evaluates if file handles are reachable before launching players.
        AvailabilityChecker(context.applicationContext)
    }

    val bookAvailabilityService: BookAvailabilityService by lazy {
        // Book Availability Application Service (Centralized reachability and status-refresh orchestration)
        // Provides the single application-layer module that can refresh persisted book/file availability state.
        BookAvailabilityService(
            bookDao = data.database.bookDao(),
            libraryRootDao = data.database.libraryRootDao(),
            availabilityChecker = availabilityChecker
        )
    }

    val subtitleFileResolver: SubtitleFileResolver by lazy {
        // Subtitle Resolver: Searches local directories to resolve matching lyrics and captions.
        SubtitleFileResolver(
            context = context.applicationContext,
            bookDao = data.database.bookDao(),
            fileReader = media.vfsFileInterface
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
            bookDao = data.database.bookDao(),
            libraryRootDao = data.database.libraryRootDao(),
            coverExtractor = coverExtractor,
            scope = s,
            fileReader = media.vfsFileInterface
        )
    }

    private val bookQueryServiceLazy = lazy {
        // Book Query Service Adapter (Dedicated book, chapter, and bookmark query implementation)
        // Playback plan construction now lives in PlaybackPlanService so this adapter no longer carries playback startup logic.
        BookQueryService(
            bookDao = data.database.bookDao(),
            chapterDao = data.database.chapterDao(),
            bookmarkDao = data.database.bookmarkDao(),
            coverRecoveryHelper = coverRecoveryHelper
        )
    }

    /**
     * Book Query Service Accessor (Preserves lazy construction while exposing the typed implementation internally)
     * The backing Lazy is retained so graph teardown can check initialization without constructing Room-backed services.
     */
    private val bookQueryService: BookQueryService
        get() = bookQueryServiceLazy.value

    val bookQueryGateway: BookQueryGateway
        get() = bookQueryService

    val playbackPlanGateway: PlaybackPlanGateway by lazy {
        // Playback Plan Service Adapter (Dedicated playback-start materialization implementation)
        // Separating this service from BookQueryService keeps the query gateway shallow and gives playback startup its own locality.
        PlaybackPlanService(
            coverUriResolver = coverUriResolver,
            bookDao = data.database.bookDao(),
            coverRecoveryHelper = coverRecoveryHelper
        )
    }

    val buildPlaybackPlanUseCase: BuildPlaybackPlanUseCase by lazy {
        // Playback Plan Use Case Wiring (Presentation entry point for plan materialization)
        // PlayerViewModel consumes this application operation instead of importing a media-core gateway directly.
        BuildPlaybackPlanUseCase(playbackPlanGateway)
    }

    private val progressGatewayLazy = lazy {
        // Progress Sync Service: Stores listening checkpoints into the local sqlite instance.
        ProgressService(
            bookDao = data.database.bookDao()
        )
    }

    /**
     * Progress Gateway Accessor (Keeps public graph API unchanged while retaining lazy lifecycle metadata)
     * Teardown can now close the service only after a runtime caller has resolved this gateway.
     */
    val progressGateway: ProgressGateway
        get() = progressGatewayLazy.value

    private val scanSchedulerLazy = lazy {
        // Media Scanner Service: Runs directory crawl passes to locate added/deleted books.
        ScanService(
            context = context,
            coverRecoveryHelper = coverRecoveryHelper,
            vfsFileInterface = media.vfsFileInterface,
            directoryListingCache = media.directoryListingCache,
            appEventSink = uiEvents.appEventSink
        )
    }

    /**
     * Scan Scheduler Accessor (Keeps scanner dependency resolution lazy and observable by teardown)
     * This prevents shutdown from creating WorkManager and scanner dependencies solely to close them.
     */
    val scanScheduler: ScanScheduler
        get() = scanSchedulerLazy.value

    val cacheEvictionCoordinator by lazy {
        // Cache Evictor: Deletes redundant cached artwork and folders during library resets.
        CacheEvictionCoordinator(
            context = context.applicationContext,
            bookDao = data.database.bookDao(),
            directoryCacheDao = data.database.directoryCacheDao(),
            directoryChildCacheDao = data.database.directoryChildCacheDao(),
            vfsRangeCache = media.vfsRangeCache
        )
    }

    private val libraryRootGatewayLazy = lazy {
        // Library Root Gateway: Manages directory registrations and coordinates folder purging.
        LibraryRootService(
            context = context,
            libraryRootDao = data.database.libraryRootDao(),
            bookDao = data.database.bookDao(),
            scanScheduler = scanScheduler,
            cacheEvictionCoordinator = cacheEvictionCoordinator
        )
    }

    /**
     * Library Root Gateway Accessor (Preserves lazy root-service construction with explicit lifecycle ownership)
     * The backing Lazy lets close() skip root-store collectors when root management was never initialized.
     */
    val libraryRootGateway: LibraryRootGateway
        get() = libraryRootGatewayLazy.value

    val coverAssetGateway: CoverAssetGateway by lazy {
        // Cover Asset Gateway Service (Dedicated custom artwork persistence adapter)
        // This service owns only user-supplied cover replacement, keeping metadata rescans out of the cover asset seam.
        CoverAssetService(
            bookDao = data.database.bookDao(),
            coverExtractor = coverExtractor
        )
    }

    val metadataRefreshGateway: MetadataRefreshGateway by lazy {
        // Metadata Refresh Gateway Service (Dedicated tag and chapter recovery adapter)
        // User-triggered rescans mutate book metadata and chapters, so they live apart from custom cover file writes.
        MetadataRefreshService(
            bookDao = data.database.bookDao(),
            chapterDao = data.database.chapterDao(),
            coverRecoveryHelper = coverRecoveryHelper,
            metadataResolver = metadataResolver,
            database = data.database
        )
    }

    val subtitleGateway: SubtitleGateway by lazy {
        // Subtitle Gateway Service (Dedicated sidecar caption loading adapter)
        // Keeping this adapter separate prevents playback subtitle parsing from depending on cover asset or metadata refresh modules.
        SubtitleService(
            subtitleResolver = subtitleFileResolver
        )
    }

    private val searchHistoryGatewayLazy = lazy {
        // Search Service Gateway: Persists history terms to data store profiles.
        SearchService(
            searchHistoryStore = data.searchHistoryStore
        )
    }

    /**
     * Search History Gateway Accessor (Keeps search history storage behind lazy graph construction)
     * The explicit Lazy backing keeps future closeable behavior visible to graph lifecycle tests.
     */
    val searchHistoryGateway: SearchHistoryGateway
        get() = searchHistoryGatewayLazy.value

    val libraryFacade: LibraryFacade by lazy {
        // Library Domain Facade: Combines the query, scanner, roots, progress, and cover adapters.
        LibraryFacade(
            bookQueryGateway = bookQueryGateway,
            bookAvailabilityGateway = bookAvailabilityService,
            progressGateway = progressGateway,
            scanScheduler = scanScheduler,
            libraryRootGateway = libraryRootGateway,
            coverAssetGateway = coverAssetGateway,
            metadataRefreshGateway = metadataRefreshGateway,
            subtitleGateway = subtitleGateway,
            searchHistoryGateway = searchHistoryGateway
        )
    }

    val homeLibraryReadModel: HomeLibraryReadModel by lazy {
        // Home Read Model Wiring (Provides the home screen with its catalog stream without passing LibraryFacade)
        // The read model is intentionally backed by the narrow book query gateway so future home projections can deepen here.
        DefaultHomeLibraryReadModel(
            bookQueryGateway = bookQueryGateway
        )
    }

    val homeLibraryUseCases: HomeLibraryUseCases by lazy {
        // Home Use Case Wiring (Collects only home-scoped commands from granular gateways)
        // This adapter keeps scan triggers, root registration, metadata refresh, and history cleanup out of LibraryViewModel.
        DefaultHomeLibraryUseCases(
            bookQueryGateway = bookQueryGateway,
            scanScheduler = scanScheduler,
            libraryRootGateway = libraryRootGateway,
            metadataRefreshGateway = metadataRefreshGateway,
            searchHistoryGateway = searchHistoryGateway
        )
    }

    val deleteLibraryRootUseCase: DeleteLibraryRootUseCase by lazy {
        // Library Deletion Use Case: Teardown logic for removing library roots.
        DeleteLibraryRootUseCase(
            playbackManager = data.playbackManager,
            bookQueryGateway = bookQueryGateway,
            libraryRootGateway = libraryRootGateway
        )
    }

    val deleteBookUseCase: DeleteBookUseCase by lazy {
        // Delete Book Use Case: Removes library references and covers of a target audiobook.
        DeleteBookUseCase(
            playbackManager = data.playbackManager,
            bookAvailabilityGateway = bookAvailabilityService,
            bookQueryGateway = bookQueryGateway
        )
    }

    override fun close() {
        // Initialized Gateway Disposal (Close only resources that were allocated by runtime callers)
        // This prevents application teardown from constructing unused Room-backed services just to close them.
        closeInitializedLibraryGraphResources(
            closeableResources = listOf(
                bookQueryServiceLazy,
                progressGatewayLazy,
                scanSchedulerLazy,
                libraryRootGatewayLazy,
                searchHistoryGatewayLazy
            ),
            recoveryScope = coverRecoveryScope
        )
    }

    val bookAvailabilityGateway: BookAvailabilityGateway
        get() = bookAvailabilityService
}
