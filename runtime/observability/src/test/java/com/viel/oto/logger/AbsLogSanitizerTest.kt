package com.viel.oto.logger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch and boundary coverage for the credential-redaction logic in [AbsLogSanitizer].
 *
 * Expected values follow the real regex pipeline in AbsLogSupport.kt and its ordering:
 * embedded http(s) url stripping -> Bearer -> sensitive JSON fields ->
 * Authorization header -> sensitive key-value pairs.
 */
class AbsLogSanitizerTest {

    // ---- Bearer token (case insensitive) ----

    @Test
    fun `sanitizeText redacts an upper case Bearer token`() {
        val result = AbsLogSanitizer.sanitizeText("header Bearer SECRETTOKEN end")
        assertEquals("header Bearer <redacted> end", result)
        assertFalse(result.contains("SECRETTOKEN"))
    }

    @Test
    fun `sanitizeText redacts a lower case bearer token and normalizes the keyword`() {
        // bearerRegex is IGNORE_CASE, the replacement is the literal "Bearer <redacted>".
        val result = AbsLogSanitizer.sanitizeText("auth bearer secrettoken")
        assertEquals("auth Bearer <redacted>", result)
        assertFalse(result.contains("secrettoken"))
    }

    // ---- JSON token / password fields ----

    @Test
    fun `sanitizeText redacts a json token field`() {
        val result = AbsLogSanitizer.sanitizeText("{\"token\":\"abc123\"}")
        assertEquals("{\"token\":\"<redacted>\"}", result)
        assertFalse(result.contains("abc123"))
    }

    @Test
    fun `sanitizeText collapses spaced json token punctuation`() {
        // The regex tolerates whitespace around : but the replacement is canonical with no spaces.
        val result = AbsLogSanitizer.sanitizeText("{ \"token\" : \"abc123\" }")
        assertEquals("{ \"token\":\"<redacted>\" }", result)
        assertFalse(result.contains("abc123"))
    }

    @Test
    fun `sanitizeText redacts a json password field`() {
        val result = AbsLogSanitizer.sanitizeText("{\"password\":\"hunter2\"}")
        assertEquals("{\"password\":\"<redacted>\"}", result)
        assertFalse(result.contains("hunter2"))
    }

    @Test
    fun `sanitizeText redacts an upper case json Token key`() {
        val result = AbsLogSanitizer.sanitizeText("{\"Token\":\"abc123\"}")
        assertEquals("{\"token\":\"<redacted>\"}", result)
        assertFalse(result.contains("abc123"))
    }

    @Test
    fun `sanitizeText redacts sensitive json scalar fields`() {
        val result = AbsLogSanitizer.sanitizeText(
            "{\"access_token\":123,\"api_key\":true,\"sig\":null}"
        )

        assertEquals(
            "{\"access_token\":\"<redacted>\",\"api_key\":\"<redacted>\",\"sig\":\"<redacted>\"}",
            result
        )
        assertFalse(result.contains("123"))
        assertFalse(result.contains("true"))
        assertFalse(result.contains("null"))
    }

    // ---- Authorization header ----

    @Test
    fun `sanitizeText redacts an Authorization colon Bearer header`() {
        val result = AbsLogSanitizer.sanitizeText("Authorization: Bearer secret-xyz")
        assertEquals("Authorization: Bearer <redacted>", result)
        assertFalse(result.contains("secret-xyz"))
    }

    @Test
    fun `sanitizeText redacts an Authorization equals Bearer header`() {
        val result = AbsLogSanitizer.sanitizeText("Authorization=Bearer secret-xyz")
        assertEquals("Authorization=Bearer <redacted>", result)
        assertFalse(result.contains("secret-xyz"))
    }

    // ---- query parameter token= / password= ----

    @Test
    fun `sanitizeText redacts a token query parameter and keeps following params`() {
        val result = AbsLogSanitizer.sanitizeText("callback token=abc123&page=2")
        assertEquals("callback token=<redacted>&page=2", result)
        assertFalse(result.contains("abc123"))
    }

    @Test
    fun `sanitizeText redacts a password query parameter`() {
        val result = AbsLogSanitizer.sanitizeText("login password=hunter2&user=demo")
        assertEquals("login password=<redacted>&user=demo", result)
        assertFalse(result.contains("hunter2"))
    }

    @Test
    fun `sanitizeText redacts expanded sensitive key-value parameters`() {
        val result = AbsLogSanitizer.sanitizeText(
            "access_token=tok refresh_token=ref api_key=key apikey=compact api-key=dash secret=s signature=sign"
        )

        assertEquals(
            "access_token=<redacted> refresh_token=<redacted> api_key=<redacted> " +
                "apikey=<redacted> api-key=<redacted> secret=<redacted> signature=<redacted>",
            result
        )
        assertFalse(result.contains("=tok"))
        assertFalse(result.contains("=ref"))
        assertFalse(result.contains("compact"))
        assertFalse(result.contains("=sign"))
    }

    @Test
    fun `sanitizeText stops query redaction at the fragment delimiter`() {
        val result = AbsLogSanitizer.sanitizeText("q token=abc#frag")
        assertEquals("q token=<redacted>#frag", result)
        assertFalse(result.contains("abc#"))
    }

    // ---- embedded http(s) url query and fragment stripping ----

    @Test
    fun `sanitizeText strips the query and fragment of an embedded url`() {
        val result = AbsLogSanitizer.sanitizeText(
            "see https://host.test/path?token=secret&sig=xyz#frag here"
        )
        assertEquals("see https://host.test/path here", result)
        assertFalse(result.contains("token=secret"))
        assertFalse(result.contains("sig=xyz"))
        assertFalse(result.contains("frag"))
    }

    @Test
    fun `sanitizeText strips the query of an embedded http url`() {
        val result = AbsLogSanitizer.sanitizeText("url=http://h.test/a/b?password=pw")
        assertEquals("url=http://h.test/a/b", result)
        assertFalse(result.contains("password=pw"))
    }

    // ---- null / empty / benign input ----

    @Test
    fun `sanitizeText maps null to an empty string`() {
        assertEquals("", AbsLogSanitizer.sanitizeText(null))
    }

    @Test
    fun `sanitizeText returns an empty string unchanged`() {
        assertEquals("", AbsLogSanitizer.sanitizeText(""))
    }

    @Test
    fun `sanitizeText leaves benign text untouched`() {
        val input = "playback resumed for chapter 3 at offset 120s"
        assertEquals(input, AbsLogSanitizer.sanitizeText(input))
    }

    // ---- multiple sensitive segments together ----

    @Test
    fun `sanitizeText redacts every sensitive segment in one pass`() {
        val result = AbsLogSanitizer.sanitizeText(
            "Authorization: Bearer tok123 {\"password\":\"pw\"} url=https://h.test/p?token=q"
        )
        assertEquals(
            "Authorization: Bearer <redacted> {\"password\":\"<redacted>\"} url=https://h.test/p",
            result
        )
        assertFalse(result.contains("tok123"))
        assertFalse(result.contains("\"pw\""))
        assertFalse(result.contains("token=q"))
    }

    // ---- sanitizeUrl: drops fragment then query before sanitizing and compacting ----

    @Test
    fun `sanitizeUrl drops the fragment and query tail`() {
        val result = AbsLogSanitizer.sanitizeUrl("https://host.test/api/items?token=secret#frag")
        assertEquals("https://host.test/api/items", result)
        assertFalse(result.contains("token=secret"))
        assertFalse(result.contains("frag"))
    }

    @Test
    fun `sanitizeUrl maps a blank input to the empty placeholder`() {
        assertEquals("<empty>", AbsLogSanitizer.sanitizeUrl(null))
        assertEquals("<empty>", AbsLogSanitizer.sanitizeUrl("   "))
    }

    @Test
    fun `sanitizeUrl truncates beyond the default max length`() {
        val longUrl = "https://host.test/" + "a".repeat(200)
        val result = AbsLogSanitizer.sanitizeUrl(longUrl)
        assertEquals(163, result.length) // 160 chars + "..."
        assertTrue(result.endsWith("..."))
    }

    // ---- compact: maxLength boundary and blank placeholder ----

    @Test
    fun `compact returns the value untouched at the exact max length`() {
        assertEquals("abcde", AbsLogSanitizer.compact("abcde", maxLength = 5))
    }

    @Test
    fun `compact truncates one character beyond the max length`() {
        assertEquals("abcde...", AbsLogSanitizer.compact("abcdef", maxLength = 5))
    }

    @Test
    fun `compact maps blank input to the empty placeholder`() {
        assertEquals("<empty>", AbsLogSanitizer.compact(""))
        assertEquals("<empty>", AbsLogSanitizer.compact("   "))
        assertEquals("<empty>", AbsLogSanitizer.compact(null))
    }

    @Test
    fun `compact still redacts secrets before truncating`() {
        val result = AbsLogSanitizer.compact("Bearer secrettoken")
        assertEquals("Bearer <redacted>", result)
        assertFalse(result.contains("secrettoken"))
    }

    // ---- shortId: default 48 char boundary ----

    @Test
    fun `shortId keeps a short id unchanged`() {
        assertEquals("item-1", AbsLogSanitizer.shortId("item-1"))
    }

    @Test
    fun `shortId truncates an id longer than the default max length`() {
        val id = "x".repeat(60)
        val result = AbsLogSanitizer.shortId(id)
        assertEquals(51, result.length) // 48 chars + "..."
        assertEquals("x".repeat(48) + "...", result)
    }

    @Test
    fun `shortId maps blank input to the empty placeholder`() {
        assertEquals("<empty>", AbsLogSanitizer.shortId(null))
        assertEquals("<empty>", AbsLogSanitizer.shortId(""))
    }
}
