package com.viel.aplayer.network

import com.viel.aplayer.shared.settings.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsafeNetworkPolicyTest {
    @Test
    fun `default settings block cleartext http and insecure tls`() {
        val defaults = AppSettings()

        assertFalse(UnsafeNetworkPolicy.isCleartextHttpAllowed("http://example.com/books", defaults))
        assertFalse(UnsafeNetworkPolicy.isInsecureTlsAllowed(defaults))
    }

    @Test
    fun `https endpoints do not require cleartext permission`() {
        val defaults = AppSettings()

        assertTrue(UnsafeNetworkPolicy.isCleartextHttpAllowed("https://example.com/books", defaults))
    }

    @Test
    fun `explicit global switches allow unsafe transports`() {
        val settings = AppSettings(
            isCleartextTrafficAllowed = true,
            isAllowInsecureTls = true
        )

        assertTrue(UnsafeNetworkPolicy.isCleartextHttpAllowed("http://example.com/books", settings))
        assertTrue(UnsafeNetworkPolicy.isInsecureTlsAllowed(settings))
    }

    @Test
    fun `local and LAN hosts bypass cleartext restriction by default`() {
        val defaults = AppSettings()

        assertTrue(UnsafeNetworkPolicy.isCleartextHttpAllowed("http://localhost/books", defaults))
        assertTrue(UnsafeNetworkPolicy.isCleartextHttpAllowed("http://127.0.0.1/books", defaults))
        assertTrue(UnsafeNetworkPolicy.isCleartextHttpAllowed("http://127.0.0.2:8080/books", defaults))
        assertTrue(UnsafeNetworkPolicy.isCleartextHttpAllowed("http://10.0.0.1/books", defaults))
        assertTrue(UnsafeNetworkPolicy.isCleartextHttpAllowed("http://172.16.0.1/books", defaults))
        assertTrue(UnsafeNetworkPolicy.isCleartextHttpAllowed("http://172.31.255.254/books", defaults))
        assertTrue(UnsafeNetworkPolicy.isCleartextHttpAllowed("http://192.168.1.100/books", defaults))
        assertTrue(UnsafeNetworkPolicy.isCleartextHttpAllowed("http://169.254.1.1/books", defaults))
        assertTrue(UnsafeNetworkPolicy.isCleartextHttpAllowed("http://my-server.local/books", defaults))
        assertTrue(UnsafeNetworkPolicy.isCleartextHttpAllowed("http://[::1]/books", defaults))
        assertTrue(UnsafeNetworkPolicy.isCleartextHttpAllowed("http://[fc00::1]:8080/books", defaults))
        assertTrue(UnsafeNetworkPolicy.isCleartextHttpAllowed("http://[fe80::1]/books", defaults))
        assertTrue(UnsafeNetworkPolicy.isCleartextHttpAllowed("http://user:pass@192.168.1.1/books", defaults))

        assertFalse(UnsafeNetworkPolicy.isCleartextHttpAllowed("http://172.15.255.255/books", defaults))
        assertFalse(UnsafeNetworkPolicy.isCleartextHttpAllowed("http://172.32.0.1/books", defaults))
        assertFalse(UnsafeNetworkPolicy.isCleartextHttpAllowed("http://192.169.1.1/books", defaults))
        assertFalse(UnsafeNetworkPolicy.isCleartextHttpAllowed("http://8.8.8.8/books", defaults))
        assertFalse(UnsafeNetworkPolicy.isCleartextHttpAllowed("http://example.local.com/books", defaults))
        assertFalse(UnsafeNetworkPolicy.isCleartextHttpAllowed("http://[2001:db8::1]/books", defaults))
    }
}
