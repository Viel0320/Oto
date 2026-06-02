package com.viel.aplayer.abs

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.media.parser.shouldSkipAutoRegeneration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsStage3LogicTest {

    @Test
    fun `abs remote books should skip auto cover regeneration`() {
        val absBook = BookEntity(
            id = "book-1",
            rootId = "root-1",
            sourceType = AudiobookSchema.SourceType.ABS_REMOTE,
            sourceRoot = "https://example.com/audiobookshelf",
            title = "ABS Book"
        )
        val localBook = absBook.copy(sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO)

        assertTrue(shouldSkipAutoRegeneration(absBook))
        assertFalse(shouldSkipAutoRegeneration(localBook))
    }
}
