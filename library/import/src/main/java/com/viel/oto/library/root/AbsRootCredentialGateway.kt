package com.viel.oto.library.root

/**
 * Narrow ABS credential operations required by library root lifecycle code.
 *
 * Library root management only needs endpoint lookup during ABS root registration and credential
 * deletion after a committed root removal, so the full ABS credential store remains owned by the
 * ABS domain.
 */
interface AbsRootCredentialGateway {
    suspend fun baseUrlFor(credentialId: String): String?
    suspend fun delete(credentialId: String?)
}
