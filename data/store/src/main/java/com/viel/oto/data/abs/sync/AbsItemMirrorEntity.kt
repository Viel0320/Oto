package com.viel.oto.data.abs.sync

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.viel.oto.data.db.AudiobookSchema

/**
 * Local mirror identity for a remote AudiobookShelf library item.
 *
 * This row links a local book to an ABS item and stores sync visibility state; it deliberately keeps
 * only stable catalog identifiers needed by data and recovery workflows.
 */
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
    val state: AudiobookSchema.AbsMirrorState
)
