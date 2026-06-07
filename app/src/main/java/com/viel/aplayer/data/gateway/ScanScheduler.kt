package com.viel.aplayer.data.gateway

import com.viel.aplayer.library.scan.ScanOutcome

/**
 * Decoupled Domain Gateway Interface (ScanScheduler)
 * Dedicated to scheduling and triggering directory file scans and metadata sync for local and WebDAV library sources.
 * 
 * Core Design Goals:
 * 1. Unify Scan Ingestions: Consolidates background periodic rescans and foreground immediate sync operations.
 * 2. Decouple WorkManager dependencies: Isolates low-level concurrency configurations, thread scheduling, and lock controls.
 */
interface ScanScheduler {

    /**
     * Foreground Immediate Ingestion (Direct sync command)
     * Triggers database updates and file rescan operations immediately, returning the shared scan outcome contract.
     * 
     * @param trigger Origin indicating sync cause (e.g. "USER", "SYSTEM", "BACKGROUND")
     */
    suspend fun syncLibrary(trigger: String = "USER"): ScanOutcome

    /**
     * Schedule Asynchronous Ingestion (Background dispatcher dispatch)
     * Dispatches rescan jobs asynchronously into the background thread pools.
     * 
     * @param trigger Origin indicating sync cause
     */
    fun scheduleLibrarySync(trigger: String = "USER")
}
