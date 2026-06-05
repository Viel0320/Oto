package com.viel.aplayer.data.gateway

import android.net.Uri
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.availability.LibraryRootAvailabilityUpdate
import kotlinx.coroutines.flow.Flow

/**
 * Decoupled Domain Gateway Interface (LibraryRootGateway)
 * Focuses on maintenance and management of library source roots (including local SAF roots and WebDAV remote hosts).
 * 
 * Core Design Goals:
 * 1. Eradicate God-Class Dependencies: Exposes dedicated read/write library root logic for upstream settings pages and scanners.
 * 2. Promote Dependency Inversion: Isolates directory authorization or network DAV credential states from core business domains.
 */
interface LibraryRootGateway {

    /**
     * Observe Registered Roots (Reactive roots flow)
     * Reactively tracks the list of all registered library source roots.
     */
    fun observeLibraryRoots(): Flow<List<LibraryRootEntity>>

    /**
     * Get Cached Roots Snapshot (Synchronous cache fetch)
     * Returns a synchronous snapshot of the library roots list currently cached in memory.
     */
    fun getCachedLibraryRoots(): List<LibraryRootEntity>

    /**
     * Register Local Storage Root (SAF directory mapping)
     * Persists a local SAF authorized tree directory as a library root.
     * 
     * @param uri Persistable SAF directory tree URI
     * @return Newly created LibraryRootEntity record
     */
    suspend fun setLibraryRoot(uri: Uri): LibraryRootEntity

    /**
     * Register WebDAV Storage Root (Remote server connection persistence)
     * Persists a WebDAV remote directory mapping, saving server credentials in a secure store.
     * 
     * @param url Physical endpoint URL of the WebDAV server
     * @param username Account login username
     * @param password Account login password
     * @param displayName Label visible in settings UI
     * @param basePath Mount directory path (e.g. '/audiobooks')
     * @return Newly created WebDAV LibraryRootEntity record
     */
    suspend fun addWebDavLibraryRoot(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String
    ): LibraryRootEntity

    /**
     * Register ABS Remote Root (Audiobookshelf library link)
     * Persists an ABS remote library reference mapping.
     * 
     * @param credentialId Stable identifier referencing credentials in ABS secure store
     * @param libraryId Remote Audiobookshelf library ID
     * @param displayName Label visible in settings UI
     * @return Newly created or updated ABS LibraryRootEntity record
     */
    suspend fun addAbsLibraryRoot(
        credentialId: String,
        libraryId: String,
        displayName: String
    ): LibraryRootEntity

    /**
     * Add SAF Root and Sync (Immediate ingestion sequence)
     * Registers a local SAF root and immediately schedules an incremental sync scan.
     */
    fun addLibraryRootAndScheduleSync(uri: Uri, trigger: String = "USER")

    /**
     * Add WebDAV Root and Sync (Immediate ingestion sequence)
     * Registers a remote WebDAV source and immediately schedules an incremental sync scan.
     * 
     * @param url Endpoint URL of the WebDAV server
     * @param username Login username
     * @param password Login password
     * @param displayName Label visible in settings UI
     * @param basePath Target remote mount folder sub-path
     * @param trigger Event source (e.g. USER, SYSTEM)
     */
    fun addWebDavLibraryRootAndScheduleSync(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String,
        trigger: String = "USER"
    )

    /**
     * Refresh Root Statuses (Reachability sanity checks)
     * Asynchronously checks active access permissions for SAF or credentials validation for remote roots.
     */
    suspend fun refreshLibraryRootStatuses()

    /**
     * Refresh Single Root Status (Targeted synchronization preflight)
     * Updates one registered root before a root-scoped sync and returns the persisted availability snapshot used for skip decisions.
     */
    suspend fun refreshLibraryRootStatus(rootId: String): LibraryRootAvailabilityUpdate?

    /**
     * Pure Data Deletion Cleanups (Cascaded cleanup transaction)
     * Purges all cache structures associated with the library root (covers, SAF permission releases, WebDAV credential deletion, Room cascades).
     * Dedicated to pure data layers, keeping playback operations decoupled.
     */
    suspend fun deleteLibraryRootDataOnly(root: LibraryRootEntity)

    /**
     * Update SAF Library Root (Update local storage directory path)
     * Replaces old persistable URI authority with new folder permission settings.
     *
     * @param id The target root identifier to update
     * @param newUri The new tree Uri selected by user via SAF
     * @return Updated library root record
     */
    suspend fun updateSafLibraryRoot(id: String, newUri: Uri): LibraryRootEntity

    /**
     * Update WebDAV Library Root (Modify endpoint or login configurations)
     * Updates URL endpoints, user parameters, and re-serializes target server credential mappings.
     *
     * @param id The target root identifier to update
     * @param url Server endpoint address URL
     * @param username Login username
     * @param password Login password
     * @param displayName Custom display label
     * @param basePath Remote directory sub-path
     * @return Updated library root record
     */
    suspend fun updateWebDavLibraryRoot(
        id: String,
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String
    ): LibraryRootEntity

    /**
     * Update ABS Library Root (Modify mirrored server references)
     * Points the existing target root record to new libraries or servers.
     *
     * @param id The target root identifier to update
     * @param credentialId Referenced security credential identifier
     * @param libraryId Targeted mirror book library ID
     * @param displayName Custom display label
     * @return Updated library root record
     */
    suspend fun updateAbsLibraryRoot(
        id: String,
        credentialId: String,
        libraryId: String,
        displayName: String
    ): LibraryRootEntity
}
