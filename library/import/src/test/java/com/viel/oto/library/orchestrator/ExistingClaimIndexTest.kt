package com.viel.oto.library.orchestrator

import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.library.FileIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExistingClaimIndexTest {

    private fun bookFile(
        id: String,
        bookId: String = "book-1",
        rootId: String = "root-1",
        index: Int = 0,
        sourcePath: String,
        sourceIdentity: String = "",
        durationMs: Long = 1_000L,
        fingerprint: String? = null
    ): BookFileEntity = BookFileEntity(
        id = id,
        bookId = bookId,
        rootId = rootId,
        index = index,
        sourcePath = sourcePath,
        sourceIdentity = sourceIdentity,
        displayName = id,
        durationMs = durationMs,
        fileSize = 0L,
        lastModified = 0L,
        fingerprint = fingerprint
    )

    private fun book(id: String, sourceType: AudiobookSchema.SourceType): BookEntity = BookEntity(
        id = id,
        rootId = "root-1",
        sourceType = sourceType,
        title = id
    )

    @Test
    fun `find resolves by path key`() {
        val file = bookFile(id = "f1", sourcePath = "dir/a.mp3", sourceIdentity = "sid-1")
        val index = ExistingClaimIndex.from(listOf(file))

        val found = index.find(FileIdentity(rootId = "root-1", sourcePath = "dir/a.mp3", sourceIdentity = ""))

        assertEquals(file, found)
    }

    @Test
    fun `find resolves by src key when path differs`() {
        val file = bookFile(id = "f1", sourcePath = "dir/a.mp3", sourceIdentity = "sid-1")
        val index = ExistingClaimIndex.from(listOf(file))

        // path key will not match, but the src key (root-1 + sid-1) will.
        val found = index.find(FileIdentity(rootId = "root-1", sourcePath = "moved/a.mp3", sourceIdentity = "sid-1"))

        assertEquals(file, found)
    }

    @Test
    fun `find returns null when no key matches`() {
        val file = bookFile(id = "f1", sourcePath = "dir/a.mp3", sourceIdentity = "sid-1")
        val index = ExistingClaimIndex.from(listOf(file))

        val found = index.find(FileIdentity(rootId = "root-1", sourcePath = "other/b.mp3", sourceIdentity = "sid-x"))

        assertNull(found)
    }

    @Test
    fun `find with same parent path returns hit`() {
        val file = bookFile(id = "f1", sourcePath = "Folder/Sub/a.mp3", sourceIdentity = "sid-1")
        val index = ExistingClaimIndex.from(listOf(file))

        val found = index.find(
            FileIdentity(rootId = "root-1", sourcePath = "Folder/Sub/a.mp3", sourceIdentity = ""),
            currentParentSourcePath = "Folder/Sub"
        )

        assertEquals(file, found)
    }

    @Test
    fun `find with same parent path differing only by case returns null`() {
        val file = bookFile(id = "f1", sourcePath = "Folder/Sub/a.mp3", sourceIdentity = "sid-1")
        val index = ExistingClaimIndex.from(listOf(file))

        val found = index.find(
            FileIdentity(rootId = "root-1", sourcePath = "Folder/Sub/a.mp3", sourceIdentity = ""),
            currentParentSourcePath = "folder/sub"
        )

        assertNull(found)
    }

    @Test
    fun `find with different parent path returns null`() {
        val file = bookFile(id = "f1", sourcePath = "folder/sub/a.mp3", sourceIdentity = "sid-1")
        val index = ExistingClaimIndex.from(listOf(file))

        val found = index.find(
            FileIdentity(rootId = "root-1", sourcePath = "folder/sub/a.mp3", sourceIdentity = ""),
            currentParentSourcePath = "different/place"
        )

        assertNull(found)
    }

    @Test
    fun `find with no slash in source path treats empty string as parent`() {
        val file = bookFile(id = "f1", sourcePath = "a.mp3", sourceIdentity = "sid-1")
        val index = ExistingClaimIndex.from(listOf(file))

        // substringBeforeLast('/', "") yields "" for a path without a slash.
        assertEquals(file, index.find(
            FileIdentity(rootId = "root-1", sourcePath = "a.mp3", sourceIdentity = ""),
            currentParentSourcePath = ""
        ))
        assertNull(index.find(
            FileIdentity(rootId = "root-1", sourcePath = "a.mp3", sourceIdentity = ""),
            currentParentSourcePath = "nope"
        ))
    }

    @Test
    fun `has mirrors find result`() {
        val file = bookFile(id = "f1", sourcePath = "dir/a.mp3", sourceIdentity = "sid-1")
        val index = ExistingClaimIndex.from(listOf(file))

        assertTrue(index.has(FileIdentity(rootId = "root-1", sourcePath = "dir/a.mp3", sourceIdentity = "")))
        assertFalse(index.has(FileIdentity(rootId = "root-1", sourcePath = "missing.mp3", sourceIdentity = "")))
    }

    @Test
    fun `completeExistingClaim returns null for empty identities`() {
        val index = ExistingClaimIndex.from(listOf(bookFile(id = "f1", sourcePath = "dir/a.mp3")))

        assertNull(index.completeExistingClaim(emptyList()))
    }

    @Test
    fun `completeExistingClaim returns null when any identity is unmatched`() {
        val file = bookFile(id = "f1", sourcePath = "dir/a.mp3", sourceIdentity = "sid-1")
        val index = ExistingClaimIndex.from(listOf(file))

        val result = index.completeExistingClaim(
            listOf(
                FileIdentity(rootId = "root-1", sourcePath = "dir/a.mp3", sourceIdentity = ""),
                FileIdentity(rootId = "root-1", sourcePath = "dir/missing.mp3", sourceIdentity = "")
            )
        )

        assertNull(result)
    }

    @Test
    fun `completeExistingClaim returns claim when all identities map to one book`() {
        val f1 = bookFile(id = "f1", bookId = "book-1", index = 0, sourcePath = "dir/a.mp3", sourceIdentity = "sid-1")
        val f2 = bookFile(id = "f2", bookId = "book-1", index = 1, sourcePath = "dir/b.mp3", sourceIdentity = "sid-2")
        val index = ExistingClaimIndex.from(listOf(f1, f2), listOf(book("book-1", AudiobookSchema.SourceType.CUE)))

        val claim = index.completeExistingClaim(
            listOf(
                FileIdentity(rootId = "root-1", sourcePath = "dir/a.mp3", sourceIdentity = ""),
                FileIdentity(rootId = "root-1", sourcePath = "dir/b.mp3", sourceIdentity = "")
            )
        )

        assertNotNull(claim)
        assertEquals("book-1", claim!!.bookId)
        assertEquals(AudiobookSchema.SourceType.CUE, claim.sourceType)
        assertEquals(setOf("f1", "f2"), claim.files.map { it.id }.toSet())
    }

    @Test
    fun `completeExistingClaim returns null when identities span multiple books`() {
        val f1 = bookFile(id = "f1", bookId = "book-1", sourcePath = "dir/a.mp3")
        val f2 = bookFile(id = "f2", bookId = "book-2", sourcePath = "dir/b.mp3")
        val index = ExistingClaimIndex.from(listOf(f1, f2))

        val claim = index.completeExistingClaim(
            listOf(
                FileIdentity(rootId = "root-1", sourcePath = "dir/a.mp3", sourceIdentity = ""),
                FileIdentity(rootId = "root-1", sourcePath = "dir/b.mp3", sourceIdentity = "")
            )
        )

        assertNull(claim)
    }

    @Test
    fun `sourceTypeForBook returns mapped type or null`() {
        val file = bookFile(id = "f1", bookId = "book-1", sourcePath = "dir/a.mp3")
        val index = ExistingClaimIndex.from(listOf(file), listOf(book("book-1", AudiobookSchema.SourceType.M3U8)))

        assertEquals(AudiobookSchema.SourceType.M3U8, index.sourceTypeForBook("book-1"))
        assertNull(index.sourceTypeForBook("book-unknown"))
    }

    @Test
    fun `from keeps first file for a duplicated key`() {
        val first = bookFile(id = "first", sourcePath = "dir/a.mp3", sourceIdentity = "")
        val second = bookFile(id = "second", sourcePath = "dir/a.mp3", sourceIdentity = "")
        val index = ExistingClaimIndex.from(listOf(first, second))

        val found = index.find(FileIdentity(rootId = "root-1", sourcePath = "dir/a.mp3", sourceIdentity = ""))

        assertEquals(first, found)
    }
}
