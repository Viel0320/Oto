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

@JsonClass(generateAdapter = true)
data class AbsAuthorizedUserDto(
    val id: String? = null,
    val username: String? = null,
    val token: String? = null
)
