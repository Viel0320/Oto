package com.viel.aplayer.data

import com.viel.aplayer.data.gateway.BookQueryGateway
import com.viel.aplayer.data.gateway.CoverGateway
import com.viel.aplayer.data.gateway.LibraryRootGateway
import com.viel.aplayer.data.gateway.ProgressGateway
import com.viel.aplayer.data.gateway.ScanScheduler
import com.viel.aplayer.data.gateway.SearchHistoryGateway

/**
 * Media Library Facade (Unified coordinator delegating specialized domain operations)
 *
 * Key Design Objectives:
 * 1. Granular Gateways: Replaces the thousand-line legacy god repository with focused domain gateway interfaces.
 * 2. Direct Delegation: Leverages Kotlin class delegation (by) to automatically route domain methods to their services.
 */
class LibraryFacade(
    private val bookQueryGateway: BookQueryGateway,
    private val progressGateway: ProgressGateway,
    private val scanScheduler: ScanScheduler,
    private val libraryRootGateway: LibraryRootGateway,
    private val coverGateway: CoverGateway,
    private val searchHistoryGateway: SearchHistoryGateway
) : BookQueryGateway by bookQueryGateway,
    ProgressGateway by progressGateway,
    ScanScheduler by scanScheduler,
    LibraryRootGateway by libraryRootGateway,
    CoverGateway by coverGateway,
    SearchHistoryGateway by searchHistoryGateway
