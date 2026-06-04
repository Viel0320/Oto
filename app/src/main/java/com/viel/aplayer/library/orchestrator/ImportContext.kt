package com.viel.aplayer.library.orchestrator

import com.viel.aplayer.library.FileIdentity
import com.viel.aplayer.library.FileInventory
import com.viel.aplayer.library.vfs.VfsFileInterface

/**
 * Import Synchronization Context
 * 
 * This class is declared as internal to limit its visibility within this module, resolving type exposure issues.
 * It carries global read-only variables and in-memory cache within the scanning lifecycle to avoid complex parameter passing between steps.
 */
internal data class ImportContext(
    // Scan Session Identifier (Unique run ID)
    // Allocated by the orchestrator for log tracking and conflict action archiving.
    val scanId: String,
    
    // Conflict Claim Ledger (Memory claim tracker)
    // In-memory 'first claim wins' ledger preventing multiple draft books from contesting the same track in a single scan.
    val runClaimLedger: RunClaimLedger = RunClaimLedger(),
    
    // Existing Claims Index (Database ownership lookup)
    // Index mapping existing book and track ownership claims currently persisted in the database.
    val existingClaimIndex: ExistingClaimIndex,
    
    // Shared File Inventory (Physical scan cache)
    // Cached physical file inventory shared among pipeline stages to prevent redundant file scans.
    var sharedInventory: FileInventory? = null,
    
    // Reserved Audio Identities (Pre-allocated files exclusion)
    // Tracks audio file identities pre-allocated by manifests (CUE/M3U8) to prevent heuristic grouping.
    val reservedAudioIdentities: MutableSet<FileIdentity> = mutableSetOf(),

    // Virtual File System Reader (Shared VFS facade)
    // Shared file reader facade for the current session to avoid redundant VFS snapshot reconstructions.
    val scopeFileReader: VfsFileInterface? = null
)