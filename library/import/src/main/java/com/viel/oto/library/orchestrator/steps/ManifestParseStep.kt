package com.viel.oto.library.orchestrator.steps

import android.content.Context
import com.viel.oto.library.FileInventory
import com.viel.oto.library.FileRef
import com.viel.oto.library.orchestrator.ImportContext
import com.viel.oto.media.manifest.CueManifestParser
import com.viel.oto.media.manifest.M3u8ManifestParser
import com.viel.oto.media.manifest.ManifestResolver
import com.viel.oto.media.manifest.ManifestSidecarSupport
import java.util.Locale

/**
 * Parses manifest files found in a scan inventory and resolves their local audio references.
 */
internal class ManifestParseStep(private val context: Context) {

    /**
     * Processes CUE and M3U8 entries from the inventory into manifest drafts.
     */
    suspend fun execute(
        input: FileInventory,
        context: ImportContext
    ): ManifestParsedResult {
        val fileReader = context.scopeFileReader ?: return ManifestParsedResult(emptyList(), emptyList())
        val cueDrafts = mutableListOf<ParsedCueDraft>()
        val m3u8Drafts = mutableListOf<ParsedM3u8Draft>()
        val audioLookup = ManifestAudioLookup(input.audioFiles)

        input.cueFiles.forEach { cue ->
            CueManifestParser.parse(
                displayName = cue.displayName,
                openStream = { fileReader.open(cue) },
                manifestFile = cue,
                directoryContext = directoryContextFor(input, cue.parentSourceKey),
                openTextFile = { textFile -> fileReader.open(textFile) }
            )?.let { result ->
                val referencedFiles = result.referencedFiles.distinct()
                val resolved = referencedFiles.mapNotNull { entry ->
                    audioLookup.findSameDirectory(cue.parentSourceKey, entry)?.let { file ->
                        entry to file.vfsKey
                    }
                }
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

        input.m3u8Files.forEach { m3u8 ->
            val result = M3u8ManifestParser.parse(
                displayName = m3u8.displayName,
                openStream = { fileReader.open(m3u8) },
                manifestFile = m3u8,
                directoryContext = directoryContextFor(input, m3u8.parentSourceKey),
                openTextFile = { textFile -> fileReader.open(textFile) }
            )
            val items = result.items
            val distinctItems = items.distinctBy { it.uri }
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
        val extensions = listOf(".mp3", ".m4b", ".m4a", ".mp4", ".aac", ".flac", ".wav", ".ogg")
        return extensions.any { value.endsWith(it, ignoreCase = true) }
    }

    private fun directoryContextFor(input: FileInventory, parentKey: String): ManifestSidecarSupport.DirectoryContext =
        ManifestSidecarSupport.DirectoryContext(
            imageFiles = input.imageFilesByParent[parentKey].orEmpty(),
            textFiles = input.textFilesByParent[parentKey].orEmpty()
        )

    private class ManifestAudioLookup(audioFiles: List<FileRef>) {
        private val byParentAndName = audioFiles.associateBy { ref -> key(ref.parentSourceKey, ref.displayName) }

        fun findSameDirectory(parentKey: String, entry: String): FileRef? {
            val fileName = ManifestResolver.sameDirectoryFileName(entry) ?: return null
            return byParentAndName[key(parentKey, fileName)]
        }

        private fun key(parentKey: String, fileName: String): String =
            "$parentKey\n${fileName.lowercase(Locale.ROOT)}"
    }
}

/**
 * Manifest drafts resolved from the current scan inventory.
 */
internal data class ManifestParsedResult(
    val cueDrafts: List<ParsedCueDraft>,
    val m3u8Drafts: List<ParsedM3u8Draft>
)

internal data class ParsedCueDraft(
    val sourceFile: FileRef,
    val result: CueManifestParser.CueResult,
    val resolvedAudioKeys: Map<String, String>,
    val missingCount: Int
)

internal data class ParsedM3u8Draft(
    val sourceFile: FileRef,
    val result: M3u8ManifestParser.M3u8Result,
    val resolvedAudioKeys: Map<String, String>,
    val missingCount: Int
)
