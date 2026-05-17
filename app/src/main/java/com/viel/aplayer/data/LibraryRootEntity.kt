package com.viel.aplayer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 媒体库授权目录实体。
 */
@Entity(tableName = "library_roots")
data class LibraryRootEntity(
    @PrimaryKey
    val id: String,
    val treeUri: String, // SAF treeUri
    val displayName: String,
    val grantedAt: Long = System.currentTimeMillis(),
    val lastScannedAt: Long = 0L,
    val status: String = "ACTIVE" // ACTIVE / REVOKED / ERROR
)
