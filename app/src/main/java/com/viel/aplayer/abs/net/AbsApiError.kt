package com.viel.aplayer.abs.net

import com.viel.aplayer.data.db.AudiobookSchema
import java.io.IOException

/**
 * ABS 网络层统一错误模型。
 *
 * 说明：
 * 1. `message` 禁止拼接 token。
 * 2. `availabilityStatus` 复用现有可用性标准件，便于 AvailabilityChecker 直接映射。
 */
class AbsApiError(
    val code: String,
    val httpStatus: Int? = null,
    val availabilityStatus: String = AudiobookSchema.AvailabilityStatus.UNKNOWN,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)
