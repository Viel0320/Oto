package com.viel.oto.abs.net.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AbsLibraryDto(
    val id: String? = null,
    val name: String? = null,
    val mediaType: String? = null
)

@JsonClass(generateAdapter = true)
data class AbsLibrariesResponseDto(
    val libraries: List<AbsLibraryDto>? = null
)
