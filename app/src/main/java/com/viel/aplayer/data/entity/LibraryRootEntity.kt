package com.viel.aplayer.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.viel.aplayer.data.db.AudiobookSchema

/**
 * Library Root Entity (Database model representing authorized media source roots)
 */
@Entity(tableName = "library_roots")
data class LibraryRootEntity(
    @PrimaryKey
    val id: String,
    // Source Distribution Type (Cross-provider dispatch key)
    // Identifies the storage protocol (e.g., SAF, WebDAV), decoupling business logic from legacy root schemas.
    val sourceType: String = AudiobookSchema.LibrarySourceType.SAF,
    // Standardized Root URI (Cross-provider unified location pointer)
    // Stores tree URIs for SAF or normalized server URLs for WebDAV remote hosts.
    val sourceUri: String,
    // Internal Sub-Path Mapping (Remote directory pointer)
    // Defines inner directory offsets (e.g., empty for SAF, or paths like '/audiobooks' for WebDAV).
    val basePath: String = "",
    // Decoupled Credential Reference (Secret isolation boundary)
    // Stores a reference key rather than raw credentials, reserving secure boundaries for future Keystore encryption.
    val credentialId: String? = null,
    // Standardized Availability Status (Decoupled health indicator)
    // Populated by a unified checker, keeping local access flags distinct from network reachability.
    val availabilityStatus: String = AudiobookSchema.AvailabilityStatus.UNKNOWN,
    // Availability Timestamp (UI and background scanning checker)
    // Logs the last verification time to distinguish between long-term offline and newly failed states.
    val lastAvailabilityCheckedAt: Long = 0L,
    // Interpretable Error Codes (Diagnostics mapping helper)
    // Logs failures (e.g., REVOKED/NOT_FOUND for SAF, or AUTH_FAILED/TIMEOUT for WebDAV servers).
    val lastAvailabilityErrorCode: String? = null,
    val displayName: String,
    val grantedAt: Long = System.currentTimeMillis(),
    val lastScannedAt: Long = 0L,
    val status: String = "ACTIVE" // ACTIVE / REVOKED / ERROR
)
