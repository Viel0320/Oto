package com.viel.aplayer.abs.net

/**
 * Authentication details container for Audiobookshelf requests.
 * Used as a request tag to pass credentials to the OkHttp interceptor.
 * It can hold either a raw token or a credential ID to look up in the database.
 */
data class AbsAuth(
    val token: String? = null,
    val credentialId: String? = null
)
