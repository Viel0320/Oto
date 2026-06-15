package com.viel.aplayer.abs.net

import com.viel.aplayer.data.db.AudiobookSchema
import java.io.IOException

/**
 * ABS Unified API Error Model (Represents a structured request exception occurring in the ABS network interface layer)
 * 
 * Rules:
 * 1. `message` is prohibited from appending raw tokens to protect server security.
 * 2. `availabilityStatus` reuses standard components to simplify mapping in the AvailabilityChecker.
 */
open class AbsApiError(
    val code: String,
    val httpStatus: Int? = null,
    val availabilityStatus: AudiobookSchema.AvailabilityStatus = AudiobookSchema.AvailabilityStatus.UNKNOWN,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)
