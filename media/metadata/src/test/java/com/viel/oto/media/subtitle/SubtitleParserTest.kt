package com.viel.oto.media.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleParserTest {

    @Test
    fun `srt parser should cap oversized cue lists`() {
        val result = SubtitleParser.parse(
            buildSrtCuePayload(cueCount = 50_000).byteInputStream(Charsets.UTF_8),
            "srt"
        )

        assertEquals(10_000, result.size)
        assertEquals("Cue 10000", result.last().text)
    }

    @Test
    fun `srt parser should cap single cue text length`() {
        val result = SubtitleParser.parse(
            buildSingleCueSrtPayload(text = "A".repeat(50_000)).byteInputStream(Charsets.UTF_8),
            "srt"
        )

        assertEquals(1, result.size)
        assertTrue(result.single().text.length <= 8_192)
    }

    @Test
    fun `player state limiter should cap externally supplied subtitle lists`() {
        val oversizedText = "B".repeat(50_000)
        val oversizedLines = List(50_000) { index ->
            SubtitleLine(
                startTime = index.toLong(),
                endTime = index + 1L,
                text = oversizedText
            )
        }

        val result = SubtitleParser.limitForPlayerState(oversizedLines)

        assertEquals(10_000, result.size)
        assertTrue(result.all { line -> line.text.length <= 8_192 })
    }

    /**
     * Creates a deterministic high-cue subtitle input.
     *
     * Builds the same shape of sidecar file that triggered the player-state growth risk while keeping the test free
     * from filesystem fixtures.
     */
    private fun buildSrtCuePayload(cueCount: Int): String =
        buildString {
            for (index in 1..cueCount) {
                append(index).append('\n')
                append("00:00:00,000 --> 00:00:01,000").append('\n')
                append("Cue ").append(index).append("\n\n")
            }
        }

    /**
     * Creates one valid cue with pathological text.
     *
     * Exercises the per-cue text budget separately from the cue-count budget so the regression pinpoints which guard
     * failed if the parser starts accepting oversized text again.
     */
    private fun buildSingleCueSrtPayload(text: String): String =
        buildString {
            append("1").append('\n')
            append("00:00:00,000 --> 00:00:01,000").append('\n')
            append(text).append("\n\n")
        }
}
