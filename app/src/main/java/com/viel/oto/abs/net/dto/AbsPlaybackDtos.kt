package com.viel.oto.abs.net.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AbsPlayRequestDto(
    val deviceInfo: AbsDeviceInfoDto? = null,
    val forceDirectPlay: Boolean? = null,
    val supportedMimeTypes: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class AbsDeviceInfoDto(
    val clientName: String? = null,
    val deviceId: String? = null
)

@JsonClass(generateAdapter = true)
data class AbsPlaybackSessionDto(
    val id: String? = null,
    val libraryItemId: String? = null,
    val audioTracks: List<AbsTrackDto>? = null
)
