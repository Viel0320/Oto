package com.viel.oto.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.viel.oto.data.db.AudiobookSchema

/**
 * Database model representing authorized media source roots.
 */
@Entity(tableName = "library_roots")
data class LibraryRootEntity(
    @PrimaryKey
    val id: String,
    val sourceType: AudiobookSchema.LibrarySourceType = AudiobookSchema.LibrarySourceType.SAF,
    val sourceUri: String,
    val basePath: String = "",
    val credentialId: String? = null,
    val availabilityStatus: AudiobookSchema.AvailabilityStatus = AudiobookSchema.AvailabilityStatus.UNKNOWN,
    val lastAvailabilityCheckedAt: Long = 0L,
    val lastAvailabilityErrorCode: String? = null,
    val displayName: String,
    val grantedAt: Long = System.currentTimeMillis(),
    val lastScannedAt: Long = 0L,
    val status: AudiobookSchema.LibraryRootStatus = AudiobookSchema.LibraryRootStatus.ACTIVE
)
