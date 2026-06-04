package com.viel.aplayer.library.orchestrator.steps

import android.content.Context
import com.viel.aplayer.library.FileInventory
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.media.manifest.CueManifestParser
import com.viel.aplayer.media.manifest.M3u8ManifestParser
import com.viel.aplayer.media.manifest.ManifestResolver
import com.viel.aplayer.media.manifest.ManifestSidecarSupport
import java.util.Locale

/**
 * Manifest Parsing Coordinator (Pipeline Step)
 *
 * This class has been refactored to remove the legacy generic ImportStep<I, O> interface and StepResult wrappers.
 * The execute method directly returns ManifestParsedResult and propagates exceptions naturally,
 * simplifying error handling and eliminating over-engineering.
 */
internal class ManifestParseStep(private val context: Context) {

    /**
     * Execute Manifest Parsing Logic (Core Execution)
     *
     * Processes the file inventory to extract manifest drafts and returns a consolidated ManifestParsedResult.
     */
    suspend fun execute(
        input: FileInventory,
        context: ImportContext
    ): ManifestParsedResult {
        // Reuse Session-Level VFS Reader (Performance Optimization)
        // Obtains the scoped file reader from ImportContext to avoid redundant allocation.
        val fileReader = context.scopeFileReader ?: return ManifestParsedResult(emptyList(), emptyList())
        val cueDrafts = mutableListOf<ParsedCueDraft>()
        val m3u8Drafts = mutableListOf<ParsedM3u8Draft>()
        // Lookup Audios via Cached Metadata (Performance Optimization)
        // Uses the pre-scanned audio list indexed by parent directory and display name to bypass repetitive disk/network directory queries.
        val audioLookup = ManifestAudioLookup(input.audioFiles)

        // Parse CUE Manifests (Incremental Scan Step 1)
        // Iterate through all discovered CUE files and resolve their tracks.
        input.cueFiles.forEach { cue ->
            CueManifestParser.parse(
                displayName = cue.displayName,
                openStream = { fileReader.open(cue) },
                manifestFile = cue,
                // Pass Scoped Sidecar Attachments (Asset Association)
                // The current directory scope already aggregates sidecar images and text descriptions.
                // This step forwards them directly to the parser for consolidated resolution without further disk checks.
                directoryContext = directoryContextFor(input, cue.parentSourceKey),
                openTextFile = { textFile -> fileReader.open(textFile) }
            )?.let { result ->
                // Resolve Tracks in Same Directory (Storage Decoupling)
                // Map the parsed relative audio filenames to corresponding pre-scanned files in the same directory.
                val referencedFiles = result.referencedFiles.distinct()
                val resolved = referencedFiles.mapNotNull { entry ->
                    audioLookup.findSameDirectory(cue.parentSourceKey, entry)?.let { file ->
                        entry to file.vfsKey
                    }
                }
                // Count Missing Tracks (Validation & Diagnostics)
                // Determine how many referenced audio files in the CUE manifest cannot be found locally.
                val missingCount = referencedFiles.count { entry ->
                    resolved.none { it.first == entry } && isAudioName(entry)
                }
                
                cueDrafts.add(ParsedCueDraft(
                     sourceFile = cue,
                     result = result,
                     resolvedAudioKeys = resolved.toMap(),
                     missingCount = missingCount
                ))
            }
        }

        // Parse M3U8 Playlists (Incremental Scan Step 2)
        // Iterate through all discovered M3U8 playlist files.
        input.m3u8Files.forEach { m3u8 ->
            // Direct Non-Null Output Consumption (Compilation Warning Prevention)
            // The M3U8 parser guarantees non-null results, so safe calls are omitted to clean up warnings.
            val result = M3u8ManifestParser.parse(
                displayName = m3u8.displayName,
                openStream = { fileReader.open(m3u8) },
                manifestFile = m3u8,
                directoryContext = directoryContextFor(input, m3u8.parentSourceKey),
                openTextFile = { textFile -> fileReader.open(textFile) }
            )
            val items = result.items
            val distinctItems = items.distinctBy { it.uri }
            // Match Local Playlist Items (Storage Decoupling)
            // Exclude remote HTTP(S) links; look up local items within the directory scan snapshot.
            val resolved = distinctItems.mapNotNull { item ->
                if (item.uri.startsWith("http://", true) || item.uri.startsWith("https://", true)) return@mapNotNull null
                audioLookup.findSameDirectory(m3u8.parentSourceKey, item.uri)?.let { file ->
                    item to file.vfsKey
                }
            }
            val missingCount = distinctItems.count { item ->
                resolved.none { it.first.uri == item.uri } && isAudioName(item.uri)
            }

            m3u8Drafts.add(ParsedM3u8Draft(
                sourceFile = m3u8,
                result = result,
                resolvedAudioKeys = resolved.associate { it.first.uri to it.second },
                missingCount = missingCount
            ))
        }

        return ManifestParsedResult(cueDrafts, m3u8Drafts)
    }

    private fun isAudioName(value: String): Boolean {
        // Treat MP4 Reference as Audio (Asset Classification)
        // Include MP4 files referenced inside CUE/M3U8 lists as valid audio candidates to avoid false-positive missing track warnings.
        val extensions = listOf(".mp3", ".m4b", ".m4a", ".mp4", ".aac", ".flac", ".wav", ".ogg")
        return extensions.any { value.endsWith(it, ignoreCase = true) }
    }

    private fun directoryContextFor(input: FileInventory, parentKey: String): ManifestSidecarSupport.DirectoryContext =
        ManifestSidecarSupport.DirectoryContext(
            imageFiles = input.imageFilesByParent[parentKey].orEmpty(),
            // Load Text Sidecars from Cache (Performance Optimization)
            // Retrieves matching text description documents from the scanned scope to eliminate VFS queries.
            textFiles = input.textFilesByParent[parentKey].orEmpty()
        )

    // Scoped Audio Index (Security & Correctness)
    // Indexes audio files strictly within the current scope to prevent manifests from referencing files with matching names in parent/sibling directories.
    private class ManifestAudioLookup(audioFiles: List<FileRef>) {
        private val byParentAndName = audioFiles.associateBy { ref -> key(ref.parentSourceKey, ref.displayName) }

        // Delegate Path Normalization (Consistency Enforcement)
        // Delegation to ManifestResolver ensures identical file alignment rules are applied during both indexing and actual parsing.
        fun findSameDirectory(parentKey: String, entry: String): FileRef? {
            val fileName = ManifestResolver.sameDirectoryFileName(entry) ?: return null
            return byParentAndName[key(parentKey, fileName)]
        }

        // Fold Case with Root Locale (Internationalization Safety)
        // Enforces case-insensitive matching that remains robust against varying system and language locales.
        private fun key(parentKey: String, fileName: String): String =
            "$parentKey\n${fileName.lowercase(Locale.ROOT)}"
    }
}

/**
 * Consolidated Manifest Parse Results (Data Holder)
 */
internal data class ManifestParsedResult(
    val cueDrafts: List<ParsedCueDraft>,
    val m3u8Drafts: List<ParsedM3u8Draft>
)

internal data class ParsedCueDraft(
    val sourceFile: FileRef,
    val result: CueManifestParser.CueResult,
    // Map Tracks to VFS File Keys (Storage Decoupling)
    // Associates tracks with stable VFS keys rather than protocol-dependent provider URIs.
    val resolvedAudioKeys: Map<String, String>,
    val missingCount: Int
)

internal data class ParsedM3u8Draft(
    val sourceFile: FileRef,
    val result: M3u8ManifestParser.M3u8Result,
    // Output VFS Keys for Playlists (Pipeline Compatibility)
    // Exports resolved tracks as VFS file keys for reuse in downstream claim, chapter, and cover stages.
    val resolvedAudioKeys: Map<String, String>,
    val missingCount: Int
)
