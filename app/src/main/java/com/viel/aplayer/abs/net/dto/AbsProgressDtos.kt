package com.viel.aplayer.abs.net.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AbsUserProgressDto(
    val currentTime: Double? = null,
    val isFinished: Boolean? = null,
    val progress: Double? = null,
    val lastUpdate: Long? = null
)
