package com.viel.aplayer.abs.net.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AbsLoginResponseDto(
    val user: AbsAuthorizedUserDto? = null
)

@JsonClass(generateAdapter = true)
data class AbsAuthorizeResponseDto(
    val user: AbsAuthorizedUserDto? = null
)

/**
 * Carries account identity and authorize-scoped progress state.
 * AudiobookShelf exposes user.mediaProgress from authorize even when item payloads omit progress, so catalog sync keeps this list available for per-item progress overlays.
 */
@JsonClass(generateAdapter = true)
data class AbsAuthorizedUserDto(
    val id: String? = null,
    val username: String? = null,
    val token: String? = null,
    val mediaProgress: List<AbsUserProgressDto>? = null
)
