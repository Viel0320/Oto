package com.viel.aplayer.logger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheDiagnosticsLoggerTest {

    @Test
    fun `message builder should keep normalized fields and redact sensitive detail`() {
        val message = CacheDiagnosticsLogger.buildLogMessage(
            cacheType = "cover",
            operation = "decode",
            hit = true,
            costMs = 12L,
            sourceHash = "abcd1234",
            sizeBytes = 4096L,
            detail = "url=https://example.com/books/cover.jpg auth=Bearer token-123"
        )

        // Cache Diagnostics Field Contract (Protects the unified P4 cache log layout)
        // The formatted message must retain the shared cache fields while replacing complete URLs and Bearer credentials before output.
        assertTrue(message.contains("cacheType=cover"))
        assertTrue(message.contains("operation=decode"))
        assertTrue(message.contains("hit=true"))
        assertTrue(message.contains("costMs=12"))
        assertTrue(message.contains("sourceHash=abcd1234"))
        assertTrue(message.contains("sizeBytes=4096"))
        assertTrue(message.contains("<url>"))
        assertTrue(message.contains("Bearer <redacted>"))
        assertFalse(message.contains("https://example.com/books/cover.jpg"))
        assertFalse(message.contains("token-123"))
    }

    @Test
    fun `message builder should normalize unknown cache types and null metrics`() {
        val message = CacheDiagnosticsLogger.buildLogMessage(
            cacheType = "remote_path",
            operation = "selectDetailCandidates",
            hit = null,
            costMs = null,
            sourceHash = null,
            sizeBytes = null,
            detail = null
        )

        // Unknown Cache Type Fallback (Prevents accidental new cache dimensions from bypassing the documented enum)
        // Unsupported cache types must be reported as unknown while nullable metrics use stable placeholder values for parsing.
        assertTrue(message.contains("cacheType=unknown"))
        assertTrue(message.contains("hit=n/a"))
        assertTrue(message.contains("costMs=-1"))
        assertTrue(message.contains("sourceHash=none"))
        assertTrue(message.contains("sizeBytes=-1"))
        assertTrue(message.contains("detail=none"))
    }

    @Test
    fun `message builder should truncate detail after redaction`() {
        val sensitiveDetail = "prefix https://example.com/${"a".repeat(160)} Bearer ${"b".repeat(80)}"

        val message = CacheDiagnosticsLogger.buildLogMessage(
            cacheType = "directory",
            operation = "replaceChildren",
            hit = false,
            costMs = 30L,
            sourceHash = "rootHash",
            sizeBytes = 3L,
            detail = sensitiveDetail
        )

        // Redaction Before Truncation (Avoids leaking partial secrets when long details exceed the compact log budget)
        // Sensitive values are removed before the final detail string is shortened, keeping long URLs and tokens out of cache diagnostics.
        assertTrue(message.length <= 220)
        assertTrue(message.contains("detail=prefix <url> Bearer <redacted>"))
        assertFalse(message.contains("example.com"))
        assertFalse(message.contains("bbbb"))
    }
}
