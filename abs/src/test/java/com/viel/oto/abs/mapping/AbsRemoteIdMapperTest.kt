package com.viel.oto.abs.mapping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsRemoteIdMapperTest {

    private val mapper = AbsRemoteIdMapper()

    @Test
    fun `remote id mapper must be stable and scoped by user plus server`() {
        val key1 = mapper.serverKey("https://example.com/AudiobookShelf", "user-1")
        val key2 = mapper.serverKey("https://example.com/AudiobookShelf", "user-1")
        val key3 = mapper.serverKey("https://example.com/AudiobookShelf", "user-2")

        assertEquals(key1, key2)
        assertTrue(key1 != key3)
        assertEquals("abs:$key1:library:lib-1", mapper.rootId(key1, "lib-1"))
        assertEquals("abs:$key1:item:item-1", mapper.bookId(key1, "item-1"))
        assertEquals("abs:$key1:item:item-1:track:3", mapper.bookFileId(key1, "item-1", 3))
    }
}
