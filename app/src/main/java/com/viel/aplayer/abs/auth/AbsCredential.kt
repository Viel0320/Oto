package com.viel.aplayer.abs.auth

import com.squareup.moshi.JsonClass

/**
 * ABS Credential Model (Represents connection attributes circulating strictly within the ACL anti-corruption boundary)
 * 
 * Rules:
 * 1. `token` is excluded from standard `toString()` implementations to prevent accidental leakage in diagnostic output logs.
 * 2. `baseUrl` must be fully normalized prior to instantiation, serving as the canonical path for sub-clients.
 */
// Pure Codegen Support: Generate a compile-time adapter for AbsCredential to eliminate runtime reflection.
@JsonClass(generateAdapter = true)
data class AbsCredential(
    val id: String,
    val baseUrl: String,
    val token: String,
    val userId: String? = null,
    val username: String? = null,
    val serverKey: String? = null
) {
    override fun toString(): String =
        "AbsCredential(id=$id, baseUrl=$baseUrl, userId=$userId, username=$username, serverKey=$serverKey, token=<redacted>)"
}
