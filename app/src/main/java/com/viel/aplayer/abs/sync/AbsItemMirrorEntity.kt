package com.viel.aplayer.abs.sync

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "abs_item_mirror",
    indices = [Index("rootId"), Index("remoteItemId"), Index("state")]
)
data class AbsItemMirrorEntity(
    @PrimaryKey
    val localBookId: String,
    val rootId: String,
    val serverKey: String,
    val remoteItemId: String,
    val lastSeenSyncRunId: String? = null,
    val lastSeenAt: Long? = null,
    val remoteUpdatedAt: Long? = null,
    val state: String
)
