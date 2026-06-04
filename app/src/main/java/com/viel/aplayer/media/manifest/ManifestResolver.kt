package com.viel.aplayer.media.manifest

import java.net.URLDecoder

/**
 * Manifest Reference Resolver (Helper for parsing relative paths inside cue/m3u8 metadata sheets)
 */
object ManifestResolver {

    // Clean Entry Filename (Normalize path separators to resolve sibling files inside the same folder)
    // Enables the orchestrator to resolve track positions directly against memory indexes without directory walk calls.
    fun sameDirectoryFileName(manifestEntryPath: String): String? {
        val decodedPath = decodeManifestEntryPath(manifestEntryPath)
        val parts = decodedPath.split('/', '\\').filter { it.isNotBlank() && it != "." }
        if (parts.size != 1 || parts.any { it == ".." }) return null
        return parts.single()
    }

    // Standard Decode Format (Centralize URL decoding to keep filename string formats unified across parsers)
    // Ensures manifest closures and legacy SAF parsing share the same filename semantics, avoiding
    // discrepancies in handling spaces, Japanese characters, or percent-encoded paths at entry points.
    private fun decodeManifestEntryPath(manifestEntryPath: String): String =
        try {
            URLDecoder.decode(manifestEntryPath, "UTF-8")
        } catch (e: Exception) {
            manifestEntryPath
        }
}
