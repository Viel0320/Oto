package com.viel.oto.media.manifest

import java.net.URLDecoder

/**
 * Helper for parsing relative paths inside cue/m3u8 metadata sheets.
 */
object ManifestResolver {

    fun sameDirectoryFileName(manifestEntryPath: String): String? {
        val decodedPath = decodeManifestEntryPath(manifestEntryPath)
        val parts = decodedPath.split('/', '\\').filter { it.isNotBlank() && it != "." }
        if (parts.size != 1 || parts.any { it == ".." }) return null
        return parts.single()
    }

    private fun decodeManifestEntryPath(manifestEntryPath: String): String =
        try {
            URLDecoder.decode(manifestEntryPath, "UTF-8")
        } catch (_: Exception) {
            manifestEntryPath
        }
}
