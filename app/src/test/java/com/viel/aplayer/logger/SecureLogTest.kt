package com.viel.aplayer.logger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureLogTest {
    @Test
    fun `diagnostic sanitizer should redact local absolute paths`() {
        val message = SecureLog.sanitizeDiagnosticText(
            "decode failed for C:\\Users\\Viel\\Audiobooks\\PrivateTitle\\chapter01.m4b"
        )

        assertTrue(message.contains("<path>"))
        assertFalse(message.contains("C:\\Users"))
        assertFalse(message.contains("PrivateTitle"))
        assertFalse(message.contains("chapter01.m4b"))
    }

    @Test
    fun `diagnostic sanitizer should strip WebDAV URL secrets`() {
        val message = SecureLog.sanitizeDiagnosticText(
            "request failed url=https://user:pass@example.test/dav/books/title.m4b?token=url-token#fragment"
        )

        assertTrue(message.contains("example.test/dav/books/title.m4b"))
        assertFalse(message.contains("user:pass"))
        assertFalse(message.contains("url-token"))
        assertFalse(message.contains("fragment"))
    }

    @Test
    fun `diagnostic sanitizer should redact bearer and password fields`() {
        val message = SecureLog.sanitizeDiagnosticText(
            "Authorization: Bearer bearer-secret password: hunter2 api_key=key-secret"
        )

        assertTrue(message.contains("Bearer <redacted>"))
        assertTrue(message.contains("password=<redacted>"))
        assertTrue(message.contains("api_key=<redacted>"))
        assertFalse(message.contains("bearer-secret"))
        assertFalse(message.contains("hunter2"))
        assertFalse(message.contains("key-secret"))
    }

    @Test
    fun `diagnostic sanitizer should redact VFS source coordinates`() {
        val message = SecureLog.sanitizeDiagnosticText(
            "sourceId=root-1:/Music/PrivateTitle/chapter01.m4b sourcePath=/storage/emulated/0/Audiobooks/PrivateTitle/chapter01.m4b"
        )

        assertTrue(message.contains("sourceId=<redacted:"))
        assertTrue(message.contains("sourcePath=<redacted:"))
        assertFalse(message.contains("root-1:/Music"))
        assertFalse(message.contains("/storage/emulated"))
        assertFalse(message.contains("PrivateTitle"))
    }

    @Test
    fun `throwable sanitizer should preserve type while redacting message`() {
        val sanitized = SecureLog.sanitizeThrowable(
            IllegalStateException("failed at C:\\Users\\Viel\\secret.txt password=hunter2")
        )

        val rendered = sanitized.toString()
        assertTrue(rendered.contains("java.lang.IllegalStateException"))
        assertTrue(rendered.contains("<path>"))
        assertTrue(rendered.contains("password=<redacted>"))
        assertFalse(rendered.contains("secret.txt"))
        assertFalse(rendered.contains("hunter2"))
    }
}
