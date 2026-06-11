package com.viel.aplayer.abs.sync

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.viel.aplayer.data.db.AudiobookSchema

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
    // AbsMirrorState Type Safe: Change state field type to AbsMirrorState enum for compile-time safety.
    val state: AudiobookSchema.AbsMirrorState
)
