package com.viel.aplayer.data

import com.viel.aplayer.data.gateway.BookAvailabilityGateway
import com.viel.aplayer.data.gateway.BookQueryGateway
import com.viel.aplayer.data.gateway.CoverAssetGateway
import com.viel.aplayer.data.gateway.LibraryRootGateway
import com.viel.aplayer.data.gateway.MetadataRefreshGateway
import com.viel.aplayer.data.gateway.ProgressGateway
import com.viel.aplayer.data.gateway.ScanScheduler
import com.viel.aplayer.data.gateway.SearchHistoryGateway
import com.viel.aplayer.data.gateway.SubtitleGateway

/**
 * Media Library Facade (Unified coordinator delegating specialized domain operations)
 *
 * Key Design Objectives:
 * 1. Granular Gateways: Replaces the thousand-line legacy god repository with focused domain gateway interfaces.
 * 2. Direct Delegation: Leverages Kotlin class delegation (by) to automatically route domain methods to their services.
 * 3. Cover/Metadata Separation: Delegates cover asset writes and metadata rescans through separate seams.
 * 4. Subtitle Separation: Delegates subtitle loading through SubtitleGateway instead of the cover gateway.
 */
class LibraryFacade(
    private val bookQueryGateway: BookQueryGateway,
    private val bookAvailabilityGateway: BookAvailabilityGateway,
    private val progressGateway: ProgressGateway,
    private val scanScheduler: ScanScheduler,
    private val libraryRootGateway: LibraryRootGateway,
    private val coverAssetGateway: CoverAssetGateway,
    private val metadataRefreshGateway: MetadataRefreshGateway,
    private val subtitleGateway: SubtitleGateway,
    private val searchHistoryGateway: SearchHistoryGateway
) : BookQueryGateway by bookQueryGateway,
    BookAvailabilityGateway by bookAvailabilityGateway,
    ProgressGateway by progressGateway,
    ScanScheduler by scanScheduler,
    LibraryRootGateway by libraryRootGateway,
    CoverAssetGateway by coverAssetGateway,
    MetadataRefreshGateway by metadataRefreshGateway,
    SubtitleGateway by subtitleGateway,
    SearchHistoryGateway by searchHistoryGateway
