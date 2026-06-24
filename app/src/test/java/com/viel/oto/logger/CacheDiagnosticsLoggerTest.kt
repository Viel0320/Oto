package com.viel.oto.logger

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

        assertTrue(message.length <= 220)
        assertTrue(message.contains("detail=prefix <url> Bearer <redacted>"))
        assertFalse(message.contains("example.com"))
        assertFalse(message.contains("bbbb"))
    }
}
