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
    val scanId: String,

    val runClaimLedger: RunClaimLedger = RunClaimLedger(),

    val existingClaimIndex: ExistingClaimIndex,

    var sharedInventory: FileInventory? = null,

    val reservedAudioIdentities: MutableSet<FileIdentity> = mutableSetOf(),

    val scopeFileReader: VfsFileInterface? = null
)