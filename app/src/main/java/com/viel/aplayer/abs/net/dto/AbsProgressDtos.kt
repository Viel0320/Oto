package com.viel.aplayer.abs.net.dto

import com.squareup.moshi.JsonClass

/**
 * User Progress Payload (Represents ABS progress returned by item details, progress probes, and authorize)
 * libraryItemId and duration are required when authorize.user.mediaProgress is the only source for first-sync read status and position reconstruction.
 */
@JsonClass(generateAdapter = true)
data class AbsUserProgressDto(
    val id: String? = null,
    val libraryItemId: String? = null,
    val currentTime: Double? = null,
    val duration: Double? = null,
    val isFinished: Boolean? = null,
    val progress: Double? = null,
    val lastUpdate: Long? = null
)
