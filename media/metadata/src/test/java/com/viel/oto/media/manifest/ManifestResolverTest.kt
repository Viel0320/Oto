package com.viel.oto.media.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the manifest sibling-name guard that keeps cue/m3u8 references inside the manifest directory.
 *
 * sameDirectoryFileName URL-decodes the entry, splits on both separator styles, and accepts only a
 * single non-blank, non-".." segment; anything that escapes the directory must resolve to null.
 */
class ManifestResolverTest {

    @Test
    fun `bare file name is accepted`() {
        assertEquals("track01.mp3", ManifestResolver.sameDirectoryFileName("track01.mp3"))
    }

    @Test
    fun `file name preserving its extension is returned verbatim`() {
        assertEquals("chapter.one.flac", ManifestResolver.sameDirectoryFileName("chapter.one.flac"))
    }

    @Test
    fun `forward-slash subdirectory path is rejected`() {
        assertNull(ManifestResolver.sameDirectoryFileName("sub/track01.mp3"))
    }

    @Test
    fun `backslash subdirectory path is rejected`() {
        assertNull(ManifestResolver.sameDirectoryFileName("sub\\track01.mp3"))
    }

    @Test
    fun `parent traversal segment is rejected`() {
        assertNull(ManifestResolver.sameDirectoryFileName("../secret.mp3"))
    }

    @Test
    fun `absolute path is rejected`() {
        assertNull(ManifestResolver.sameDirectoryFileName("/etc/passwd"))
    }

    @Test
    fun `url-encoded separator is decoded then rejected`() {
        // %2F decodes to '/', so the split sees two segments and the guard rejects it.
        assertNull(ManifestResolver.sameDirectoryFileName("sub%2Ftrack01.mp3"))
    }

    @Test
    fun `url-encoded parent traversal is decoded then rejected`() {
        // %2E%2E%2F decodes to "../", surfacing the ".." segment the guard blocks.
        assertNull(ManifestResolver.sameDirectoryFileName("%2E%2E%2Fsecret.mp3"))
    }

    @Test
    fun `leading dot-slash current directory segment is filtered then name accepted`() {
        // "." segments are dropped, leaving a single real file name.
        assertEquals("track01.mp3", ManifestResolver.sameDirectoryFileName("./track01.mp3"))
    }

    @Test
    fun `blank entry resolves to null`() {
        assertNull(ManifestResolver.sameDirectoryFileName(""))
    }

    @Test
    fun `only separators resolves to null`() {
        assertNull(ManifestResolver.sameDirectoryFileName("/"))
    }

    @Test
    fun `url-encoded space is decoded inside an accepted name`() {
        // %20 decodes to a space; still a single segment, so it stays a valid sibling name.
        assertEquals("track 01.mp3", ManifestResolver.sameDirectoryFileName("track%2001.mp3"))
    }

    @Test
    fun `file name containing dot-dot substring but not a segment is accepted`() {
        // ".." must be a whole path segment to be rejected; an embedded substring is a legal name.
        assertEquals("vol..1.mp3", ManifestResolver.sameDirectoryFileName("vol..1.mp3"))
    }

    @Test
    fun `malformed percent-encoding falls back to the raw entry`() {
        // A lone '%' makes URLDecoder throw; the catch falls back to the raw string, still a single segment.
        assertEquals("100%bonus.mp3", ManifestResolver.sameDirectoryFileName("100%bonus.mp3"))
    }
}
