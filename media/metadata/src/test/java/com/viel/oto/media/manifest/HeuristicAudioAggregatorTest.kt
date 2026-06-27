package com.viel.oto.media.manifest

import com.viel.oto.library.FileRef
import com.viel.oto.media.AudiobookMetadata
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the loose-file aggregation predicate that decides whether scattered audio files become one book.
 *
 * shouldAggregate has three branches: fewer than two files never aggregate; exactly two files require a
 * shared non-blank album; three or more aggregate on a shared album OR sequential trailing numbers.
 */
class HeuristicAudioAggregatorTest {

    @Test
    fun `single file never aggregates`() {
        val files = listOf(ref(name = "only.mp3", album = "Book"))

        assertFalse(HeuristicAudioAggregator.shouldAggregate(files))
    }

    @Test
    fun `empty list never aggregates`() {
        assertFalse(HeuristicAudioAggregator.shouldAggregate(emptyList()))
    }

    @Test
    fun `two files with the same non-blank album aggregate`() {
        val files = listOf(
            ref(name = "a.mp3", album = "Same Book"),
            ref(name = "b.mp3", album = "Same Book")
        )

        assertTrue(HeuristicAudioAggregator.shouldAggregate(files))
    }

    @Test
    fun `two files with different albums do not aggregate`() {
        val files = listOf(
            ref(name = "a.mp3", album = "Book One"),
            ref(name = "b.mp3", album = "Book Two")
        )

        assertFalse(HeuristicAudioAggregator.shouldAggregate(files))
    }

    @Test
    fun `two files with blank album do not aggregate even with sequential names`() {
        // The two-file branch only checks the album; sequential names are not consulted.
        val files = listOf(
            ref(name = "track1.mp3", album = ""),
            ref(name = "track2.mp3", album = "")
        )

        assertFalse(HeuristicAudioAggregator.shouldAggregate(files))
    }

    @Test
    fun `three files with a shared album aggregate regardless of names`() {
        val files = listOf(
            ref(name = "intro.mp3", album = "Anthology"),
            ref(name = "middle.mp3", album = "Anthology"),
            ref(name = "outro.mp3", album = "Anthology")
        )

        assertTrue(HeuristicAudioAggregator.shouldAggregate(files))
    }

    @Test
    fun `three files with blank albums but sequential names aggregate`() {
        // Uses a digit-free extension so the trailing number reflects the real chapter sequence
        // rather than the extension digits (see the mp3-masking test below).
        val files = listOf(
            ref(name = "chapter1.flac", album = ""),
            ref(name = "chapter2.flac", album = ""),
            ref(name = "chapter3.flac", album = "")
        )

        assertTrue(HeuristicAudioAggregator.shouldAggregate(files))
    }

    @Test
    fun `sequential mp3 names do not aggregate because the extension digit masks the sequence`() {
        // Suspicious production edge: hasSequentialNames takes the LAST digit run of the full
        // displayName, which for ".mp3" is always "3". So genuinely sequential .mp3 files all
        // resolve to 3,3,3 and fail the monotonic check. Documented here, not a test of intent.
        val files = listOf(
            ref(name = "chapter1.mp3", album = ""),
            ref(name = "chapter2.mp3", album = ""),
            ref(name = "chapter3.mp3", album = "")
        )

        assertFalse(HeuristicAudioAggregator.shouldAggregate(files))
    }

    @Test
    fun `three files with neither shared album nor sequential names do not aggregate`() {
        val files = listOf(
            ref(name = "alpha.mp3", album = ""),
            ref(name = "beta.mp3", album = ""),
            ref(name = "gamma.mp3", album = "")
        )

        assertFalse(HeuristicAudioAggregator.shouldAggregate(files))
    }

    @Test
    fun `non-monotonic trailing numbers do not aggregate`() {
        // hasSequentialNames requires each number to be >= predecessor + 1; a drop breaks the run.
        val files = listOf(
            ref(name = "part5.mp3", album = ""),
            ref(name = "part3.mp3", album = ""),
            ref(name = "part4.mp3", album = "")
        )

        assertFalse(HeuristicAudioAggregator.shouldAggregate(files))
    }

    @Test
    fun `a file missing any trailing number breaks the sequential branch`() {
        val files = listOf(
            ref(name = "chapter1.mp3", album = ""),
            ref(name = "interlude.mp3", album = ""),
            ref(name = "chapter3.mp3", album = "")
        )

        assertFalse(HeuristicAudioAggregator.shouldAggregate(files))
    }

    @Test
    fun `partially blank albums are treated as not shared`() {
        // sameAlbum requires every album be non-blank and equal; one blank fails it, falling back to names.
        val files = listOf(
            ref(name = "x.mp3", album = "Book"),
            ref(name = "y.mp3", album = ""),
            ref(name = "z.mp3", album = "Book")
        )

        assertFalse(HeuristicAudioAggregator.shouldAggregate(files))
    }

    private fun ref(name: String, album: String): AudioMetadataRef =
        AudioMetadataRef(
            file = fileRef(name),
            metadata = AudiobookMetadata(
                title = "",
                author = "",
                narrator = "",
                album = album,
                description = "",
                year = "",
                durationMs = 0L
            )
        )

    private fun fileRef(name: String): FileRef =
        FileRef(
            rootId = "root",
            sourcePath = "/books/$name",
            sourceIdentity = name,
            parentSourcePath = "/books",
            parentSourceKey = "root:/books",
            parentSourceIdentity = "books",
            displayName = name,
            fileSize = 1L,
            lastModified = 1L
        )
}
