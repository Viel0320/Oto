package com.viel.oto.abs.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Boundary and branch coverage for [AbsUrlResolver].
 *
 * Expected values are derived from the real source: blank/whitespace/slash-only input
 * fails the `require` precondition (IllegalArgumentException), while unparseable input
 * throws AbsApiError with code INVALID_BASE_URL.
 */
class AbsUrlResolverTest {

    // ---- resolveBaseUrl: normalization ----

    @Test
    fun `resolveBaseUrl keeps a plain url unchanged`() {
        val url = AbsUrlResolver.resolveBaseUrl("https://abs.example.test")
        assertEquals("https://abs.example.test/", url.toString())
    }

    @Test
    fun `resolveBaseUrl trims surrounding whitespace`() {
        val url = AbsUrlResolver.resolveBaseUrl("   https://abs.example.test   ")
        assertEquals("https://abs.example.test/", url.toString())
    }

    @Test
    fun `resolveBaseUrl trims a single trailing slash`() {
        val url = AbsUrlResolver.resolveBaseUrl("https://abs.example.test/")
        assertEquals("https://abs.example.test/", url.toString())
    }

    @Test
    fun `resolveBaseUrl trims multiple trailing slashes`() {
        val url = AbsUrlResolver.resolveBaseUrl("https://abs.example.test///")
        assertEquals("https://abs.example.test/", url.toString())
    }

    @Test
    fun `resolveBaseUrl preserves a sub path while trimming the trailing slash`() {
        val url = AbsUrlResolver.resolveBaseUrl("https://host.test/AudiobookShelf/")
        assertEquals("https://host.test/AudiobookShelf", url.toString())
    }

    @Test
    fun `resolveBaseUrl trims whitespace and trailing slashes together`() {
        val url = AbsUrlResolver.resolveBaseUrl("  https://host.test/base//  ")
        assertEquals("https://host.test/base", url.toString())
    }

    @Test
    fun `resolveBaseUrl accepts cleartext http scheme`() {
        val url = AbsUrlResolver.resolveBaseUrl("http://10.0.0.5:13378")
        assertEquals("http://10.0.0.5:13378/", url.toString())
    }

    // ---- resolveBaseUrl: blank precondition (require -> IllegalArgumentException) ----

    @Test
    fun `resolveBaseUrl rejects an empty string`() {
        assertThrows(IllegalArgumentException::class.java) {
            AbsUrlResolver.resolveBaseUrl("")
        }
    }

    @Test
    fun `resolveBaseUrl rejects a whitespace only string`() {
        assertThrows(IllegalArgumentException::class.java) {
            AbsUrlResolver.resolveBaseUrl("    ")
        }
    }

    @Test
    fun `resolveBaseUrl throws structured error for a slash only string`() {
        // trim() leaves "/", which is NOT blank so the require precondition passes;
        // trimEnd('/') then reduces it to "" which fails toHttpUrlOrNull and throws AbsApiError.
        val error = assertThrows(AbsApiError::class.java) {
            AbsUrlResolver.resolveBaseUrl("/")
        }
        assertEquals("INVALID_BASE_URL", error.code)
    }

    @Test
    fun `resolveBaseUrl throws structured error for a multiple slash only string`() {
        val error = assertThrows(AbsApiError::class.java) {
            AbsUrlResolver.resolveBaseUrl("///")
        }
        assertEquals("INVALID_BASE_URL", error.code)
    }

    @Test
    fun `resolveBaseUrl throws structured error for whitespace wrapped slashes`() {
        // After trim() the value is "//", not blank, so it reaches the parse branch and fails there.
        val error = assertThrows(AbsApiError::class.java) {
            AbsUrlResolver.resolveBaseUrl("  //  ")
        }
        assertEquals("INVALID_BASE_URL", error.code)
    }

    // ---- resolveBaseUrl: unparseable url (AbsApiError) ----

    @Test
    fun `resolveBaseUrl throws structured error for a non http url`() {
        val error = assertThrows(AbsApiError::class.java) {
            AbsUrlResolver.resolveBaseUrl("ftp://host.test")
        }
        assertEquals("INVALID_BASE_URL", error.code)
    }

    @Test
    fun `resolveBaseUrl throws structured error for a bare host without scheme`() {
        val error = assertThrows(AbsApiError::class.java) {
            AbsUrlResolver.resolveBaseUrl("abs.example.test")
        }
        assertEquals("INVALID_BASE_URL", error.code)
    }

    // ---- resolveApiUrl ----

    @Test
    fun `resolveApiUrl appends api and the endpoint segment`() {
        val url = AbsUrlResolver.resolveApiUrl("https://host.test", "libraries")
        assertEquals("https://host.test/api/libraries", url.toString())
    }

    @Test
    fun `resolveApiUrl normalizes the base url trailing slash before appending`() {
        val url = AbsUrlResolver.resolveApiUrl("https://host.test/", "authorize")
        assertEquals("https://host.test/api/authorize", url.toString())
    }

    @Test
    fun `resolveApiUrl preserves a base sub path`() {
        val url = AbsUrlResolver.resolveApiUrl("https://host.test/AudiobookShelf/", "me")
        assertEquals("https://host.test/AudiobookShelf/api/me", url.toString())
    }

    @Test
    fun `resolveApiUrl encodes a leading slash inside the endpoint as a single segment`() {
        // addPathSegment treats the argument as one segment and percent-encodes the leading slash,
        // so a caller-supplied leading slash does NOT create an extra empty path segment.
        val url = AbsUrlResolver.resolveApiUrl("https://host.test", "/libraries")
        assertEquals("https://host.test/api/%2Flibraries", url.toString())
    }

    @Test
    fun `resolveApiUrl propagates the blank base precondition`() {
        assertThrows(IllegalArgumentException::class.java) {
            AbsUrlResolver.resolveApiUrl("", "libraries")
        }
    }

    // ---- resolveCoverUrl ----

    @Test
    fun `resolveCoverUrl builds the items cover endpoint`() {
        val url = AbsUrlResolver.resolveCoverUrl("https://host.test", "item-1")
        assertEquals("https://host.test/api/items/item-1/cover", url.toString())
    }

    @Test
    fun `resolveCoverUrl normalizes the base trailing slash`() {
        val url = AbsUrlResolver.resolveCoverUrl("https://host.test///", "item-9")
        assertEquals("https://host.test/api/items/item-9/cover", url.toString())
    }

    @Test
    fun `resolveCoverUrl percent encodes an item id with special characters`() {
        val url = AbsUrlResolver.resolveCoverUrl("https://host.test", "a b/c")
        assertEquals("https://host.test/api/items/a%20b%2Fc/cover", url.toString())
    }

    @Test
    fun `resolveCoverUrl propagates the blank base precondition`() {
        assertThrows(IllegalArgumentException::class.java) {
            AbsUrlResolver.resolveCoverUrl("   ", "item-1")
        }
    }
}
