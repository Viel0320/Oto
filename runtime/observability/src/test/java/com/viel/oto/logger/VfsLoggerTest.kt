package com.viel.oto.logger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VfsLoggerTest {

    @Test
    fun `webdav error log message should redact userinfo query and fragment`() {
        val message = VfsLogger.buildWebDavErrorMessage(
            url = "https://user:pass@example.com/dav/book.mp3?token=secret#frag",
            status = "TIMEOUT",
            errorClass = "SocketTimeoutException"
        )

        assertTrue(message.contains("url=https://example.com/dav/book.mp3"))
        assertTrue(message.contains("status=TIMEOUT"))
        assertTrue(message.contains("exception=SocketTimeoutException"))
        assertFalse(message.contains("user"))
        assertFalse(message.contains("pass"))
        assertFalse(message.contains("token=secret"))
        assertFalse(message.contains("#frag"))
    }
}
