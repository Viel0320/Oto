package com.viel.aplayer.abs.net.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AbsStatusDto(
    val isInit: Boolean? = null,
    val language: String? = null,
    val configPath: String? = null,
    val metadataPath: String? = null,
    val ffmpegPath: String? = null,
    val serverVersion: String? = null
)
