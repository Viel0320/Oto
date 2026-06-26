package com.viel.oto.abs.root

import com.viel.oto.abs.auth.AbsCredentialStore
import com.viel.oto.library.root.AbsRootCredentialGateway

/**
 * Adapts the ABS credential store to the library root lifecycle seam.
 *
 * The adapter exposes only endpoint lookup and post-commit deletion, preventing library root code
 * from depending on token fields or broader ABS credential persistence behavior.
 */
class AbsRootCredentialGatewayAdapter(
    private val credentialStore: AbsCredentialStore
) : AbsRootCredentialGateway {
    override suspend fun baseUrlFor(credentialId: String): String? =
        credentialStore.get(credentialId)?.baseUrl

    override suspend fun delete(credentialId: String?) {
        credentialStore.delete(credentialId)
    }
}
