package com.viel.oto.abs.sync

import androidx.room.Entity
import androidx.room.PrimaryKey

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
