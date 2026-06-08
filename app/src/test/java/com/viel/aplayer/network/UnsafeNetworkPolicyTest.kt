package com.viel.aplayer.network

import com.viel.aplayer.data.store.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsafeNetworkPolicyTest {
    @Test
    fun `default settings block cleartext http and insecure tls`() {
        val defaults = AppSettings()

        // Default Unsafe Transport Policy (Verifies first-run settings do not opt into insecure network behavior)
        // Cleartext HTTP and TLS certificate bypasses must require explicit user interaction before remote credentials or streams can use them.
        assertFalse(UnsafeNetworkPolicy.isCleartextHttpAllowed("http://server.local/books", defaults))
        assertFalse(UnsafeNetworkPolicy.isInsecureTlsAllowed(defaults))
    }

    @Test
    fun `https endpoints do not require cleartext permission`() {
        val defaults = AppSettings()

        // HTTPS Cleartext Exemption (Ensures encrypted endpoints remain usable with the strict default policy)
        // The cleartext switch only gates http:// endpoints and should not block normal HTTPS WebDAV or ABS servers.
        assertTrue(UnsafeNetworkPolicy.isCleartextHttpAllowed("https://server.local/books", defaults))
    }

    @Test
    fun `explicit global switches allow unsafe transports`() {
        val settings = AppSettings(
            isCleartextTrafficAllowed = true,
            isAllowInsecureTls = true
        )

        // Explicit Unsafe Transport Opt-In (Confirms the policy honors user-controlled compatibility switches)
        // Tests use the same global settings model as production so no provider can introduce a separate bypass.
        assertTrue(UnsafeNetworkPolicy.isCleartextHttpAllowed("http://server.local/books", settings))
        assertTrue(UnsafeNetworkPolicy.isInsecureTlsAllowed(settings))
    }
}
