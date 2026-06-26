package com.viel.oto.library.orchestrator

import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.library.FileIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictResolverTest {

    private val resolver = ConflictResolver()

    private fun source(sourceType: AudiobookSchema.SourceType): ImportSourceRef = ImportSourceRef(
        sourceType = sourceType,
        sourceUri = "content://import/${sourceType.name}",
        displayName = sourceType.name
    )

    private fun bookFile(
        id: String,
        bookId: String,
        sourcePath: String,
        sourceIdentity: String = "",
        index: Int = 0
    ): BookFileEntity = BookFileEntity(
        id = id,
        bookId = bookId,
        rootId = "root-1",
        index = index,
        sourcePath = sourcePath,
        sourceIdentity = sourceIdentity,
        displayName = id,
        durationMs = 1_000L,
        fileSize = 0L,
        lastModified = 0L
    )

    private fun book(id: String, sourceType: AudiobookSchema.SourceType): BookEntity = BookEntity(
        id = id,
        rootId = "root-1",
        sourceType = sourceType,
        title = id
    )

    private fun identity(sourcePath: String): FileIdentity =
        FileIdentity(rootId = "root-1", sourcePath = sourcePath, sourceIdentity = "")

    private fun reservation(
        reserved: Boolean = false,
        existingConflicts: List<BookFileEntity> = emptyList(),
        runConflicts: List<ImportSourceRef> = emptyList()
    ): ReservationResult = ReservationResult(
        reserved = reserved,
        existingConflicts = existingConflicts,
        runConflicts = runConflicts
    )

    @Test
    fun `reserved with no missing files creates ready book`() {
        val result = resolver.resolveManifestOwnership(
            source = source(AudiobookSchema.SourceType.CUE),
            claimedIdentities = listOf(identity("dir/a.mp3")),
            reservation = reservation(reserved = true),
            existingClaimIndex = ExistingClaimIndex.from(emptyList()),
            missingCount = 0
        )

        assertEquals(ConflictResolution.CreateBook(AudiobookSchema.BookStatus.READY), result)
    }

    @Test
    fun `reserved with missing files creates partial book`() {
        val result = resolver.resolveManifestOwnership(
            source = source(AudiobookSchema.SourceType.CUE),
            claimedIdentities = listOf(identity("dir/a.mp3")),
            reservation = reservation(reserved = true),
            existingClaimIndex = ExistingClaimIndex.from(emptyList()),
            missingCount = 2
        )

        assertEquals(ConflictResolution.CreateBook(AudiobookSchema.BookStatus.PARTIAL), result)
    }

    @Test
    fun `run conflicts skip even when not reserved`() {
        val result = resolver.resolveManifestOwnership(
            source = source(AudiobookSchema.SourceType.CUE),
            claimedIdentities = listOf(identity("dir/a.mp3")),
            reservation = reservation(reserved = false, runConflicts = listOf(source(AudiobookSchema.SourceType.M3U8))),
            existingClaimIndex = ExistingClaimIndex.from(emptyList()),
            missingCount = 0
        )

        assertEquals(ConflictResolution.Skip, result)
    }

    @Test
    fun `complete claim with no missing files refreshes the book`() {
        val f1 = bookFile(id = "f1", bookId = "book-1", sourcePath = "dir/a.mp3", index = 0)
        val f2 = bookFile(id = "f2", bookId = "book-1", sourcePath = "dir/b.mp3", index = 1)
        val index = ExistingClaimIndex.from(listOf(f1, f2), listOf(book("book-1", AudiobookSchema.SourceType.CUE)))

        val result = resolver.resolveManifestOwnership(
            source = source(AudiobookSchema.SourceType.CUE),
            claimedIdentities = listOf(identity("dir/a.mp3"), identity("dir/b.mp3")),
            reservation = reservation(reserved = false),
            existingClaimIndex = index,
            missingCount = 0
        )

        assertTrue(result is ConflictResolution.RefreshBook)
        val refresh = result as ConflictResolution.RefreshBook
        assertEquals("book-1", refresh.bookId)
        assertEquals(setOf("f1", "f2"), refresh.files.map { it.id }.toSet())
    }

    @Test
    fun `complete claim with missing files replaces the same book`() {
        val f1 = bookFile(id = "f1", bookId = "book-1", sourcePath = "dir/a.mp3", index = 0)
        val index = ExistingClaimIndex.from(listOf(f1), listOf(book("book-1", AudiobookSchema.SourceType.CUE)))

        val result = resolver.resolveManifestOwnership(
            source = source(AudiobookSchema.SourceType.CUE),
            claimedIdentities = listOf(identity("dir/a.mp3")),
            reservation = reservation(reserved = false),
            existingClaimIndex = index,
            missingCount = 1
        )

        assertEquals(
            ConflictResolution.ReplaceBooks(
                bookIds = listOf("book-1"),
                bookStatus = AudiobookSchema.BookStatus.PARTIAL
            ),
            result
        )
    }

    @Test
    fun `no complete claim and no existing conflicts skips`() {
        val result = resolver.resolveManifestOwnership(
            source = source(AudiobookSchema.SourceType.CUE),
            claimedIdentities = emptyList(),
            reservation = reservation(reserved = false, existingConflicts = emptyList()),
            existingClaimIndex = ExistingClaimIndex.from(emptyList()),
            missingCount = 0
        )

        assertEquals(ConflictResolution.Skip, result)
    }

    @Test
    fun `higher priority incoming replaces lower priority persisted owner`() {
        // Existing owner is M3U8 (priority 2); incoming is CUE (priority 3) so it wins.
        val existingFile = bookFile(id = "ef1", bookId = "existing-book", sourcePath = "dir/a.mp3")
        val index = ExistingClaimIndex.from(
            listOf(existingFile),
            listOf(book("existing-book", AudiobookSchema.SourceType.M3U8))
        )

        val result = resolver.resolveManifestOwnership(
            // claimedIdentities empty so completeExistingClaim returns null and the priority branch runs.
            source = source(AudiobookSchema.SourceType.CUE),
            claimedIdentities = emptyList(),
            reservation = reservation(reserved = false, existingConflicts = listOf(existingFile)),
            existingClaimIndex = index,
            missingCount = 0
        )

        assertEquals(
            ConflictResolution.ReplaceBooks(
                bookIds = listOf("existing-book"),
                bookStatus = AudiobookSchema.BookStatus.READY
            ),
            result
        )
    }

    @Test
    fun `lower priority incoming skips against higher priority persisted owner`() {
        // Existing owner is CUE (priority 3); incoming is M3U8 (priority 2) so it cannot replace.
        val existingFile = bookFile(id = "ef1", bookId = "existing-book", sourcePath = "dir/a.mp3")
        val index = ExistingClaimIndex.from(
            listOf(existingFile),
            listOf(book("existing-book", AudiobookSchema.SourceType.CUE))
        )

        val result = resolver.resolveManifestOwnership(
            source = source(AudiobookSchema.SourceType.M3U8),
            claimedIdentities = emptyList(),
            reservation = reservation(reserved = false, existingConflicts = listOf(existingFile)),
            existingClaimIndex = index,
            missingCount = 0
        )

        assertEquals(ConflictResolution.Skip, result)
    }

    @Test
    fun `equal priority incoming skips against persisted owner`() {
        // Both M3U8 (priority 2); incoming is not strictly greater so it skips.
        val existingFile = bookFile(id = "ef1", bookId = "existing-book", sourcePath = "dir/a.mp3")
        val index = ExistingClaimIndex.from(
            listOf(existingFile),
            listOf(book("existing-book", AudiobookSchema.SourceType.M3U8))
        )

        val result = resolver.resolveManifestOwnership(
            source = source(AudiobookSchema.SourceType.M3U8),
            claimedIdentities = emptyList(),
            reservation = reservation(reserved = false, existingConflicts = listOf(existingFile)),
            existingClaimIndex = index,
            missingCount = 0
        )

        assertEquals(ConflictResolution.Skip, result)
    }

    @Test
    fun `incoming over unknown source type persisted owner replaces as priority other`() {
        // No book entity mapping for the bookId, so sourceTypeForBook returns null -> priority 1 (other).
        // Incoming M3U8 (priority 2) beats it.
        val existingFile = bookFile(id = "ef1", bookId = "existing-book", sourcePath = "dir/a.mp3")
        val index = ExistingClaimIndex.from(listOf(existingFile))

        val result = resolver.resolveManifestOwnership(
            source = source(AudiobookSchema.SourceType.M3U8),
            claimedIdentities = emptyList(),
            reservation = reservation(reserved = false, existingConflicts = listOf(existingFile)),
            existingClaimIndex = index,
            missingCount = 0
        )

        assertEquals(
            ConflictResolution.ReplaceBooks(
                bookIds = listOf("existing-book"),
                bookStatus = AudiobookSchema.BookStatus.READY
            ),
            result
        )
    }
}
