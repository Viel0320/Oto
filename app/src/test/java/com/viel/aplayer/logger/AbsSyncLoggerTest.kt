package com.viel.aplayer.logger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsSyncLoggerTest {

    @Test
    fun `item materialization failure message should redact secrets and URL queries`() {
        val message = AbsSyncLogger.buildItemMaterializationFailureMessage(
            rootId = "abs-root-1234567890",
            itemId = "abs-item-0987654321",
            errorClass = "JsonDataException",
            message = "failed with Bearer secret-token password=abc url=https://example.com/items/1?apiKey=hidden&token=url-token#trace"
        )

        assertTrue(message.contains("item materialization failure"))
        assertTrue(message.contains("rootId=abs-root-1234567890"))
        assertTrue(message.contains("itemId=abs-item-0987654321"))
        assertTrue(message.contains("errorClass=JsonDataException"))
        assertTrue(message.contains("Bearer <redacted>"))
        assertTrue(message.contains("password=<redacted>"))
        assertTrue(message.contains("https://example.com/items/1"))
        assertFalse(message.contains("secret-token"))
        assertFalse(message.contains("abc"))
        assertFalse(message.contains("?apiKey=hidden"))
        assertFalse(message.contains("url-token"))
        assertFalse(message.contains("#trace"))
    }
}
