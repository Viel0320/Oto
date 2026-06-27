package com.viel.oto.media.manifest

import java.net.URLDecoder

/**
 * Helper for parsing relative paths inside cue/m3u8 metadata sheets.
 */
object ManifestResolver {
    private val ENCODED_PATH_CONTROL_TOKEN = Regex("%(?:2e|2f|5c)", RegexOption.IGNORE_CASE)

    /**
     * Extracts only same-directory manifest references.
     * The single decode pass rejects any remaining encoded path-control token so later decoders cannot reinterpret the accepted name as traversal or a nested path.
     */
    fun sameDirectoryFileName(manifestEntryPath: String): String? {
        val decodedPath = decodeManifestEntryPath(manifestEntryPath)
        if (ENCODED_PATH_CONTROL_TOKEN.containsMatchIn(decodedPath)) return null
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
