package com.viel.oto.data.abs.sync

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Durable synchronization checkpoint for one ABS library root.
 *
 * The row tracks local scheduling and error metadata for catalog sync; server protocol fields are
 * reduced to stable identifiers before reaching Room.
 */
@Entity(tableName = "abs_sync_state")
data class AbsSyncStateEntity(
    @PrimaryKey
    val rootId: String,
    val serverKey: String,
    val libraryId: String,
    val lastFullSyncAt: Long? = null,
    val lastIncrementalSyncAt: Long? = null,
    val serverVersion: String? = null,
    val lastError: String? = null,
    val fullListFingerprint: String? = null
)
