package com.viel.aplayer.media.manifest

import com.viel.aplayer.library.FileRef
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Parser Sidecar Manager (Centralize sidecar image/text evaluation routines to decouple directory traversal from step layers)
 *
 * Consolidates helper logic for locating nearby covers and text summaries,
 * ensuring CueManifestParser, M3u8ManifestParser, and HeuristicAudioAggregator
 * share the same constraints:
 * 1. Sidecar artwork lookup priority.
 * 2. Txt description identification and loading.
 * 3. Text decoding and length clipping limits.
 *
 * This allows subsequent import steps to purely consume parser output,
 * preventing redundant directory listing and parallel rules implementation in execution stages.
 */
object ManifestSidecarSupport {

    data class DirectoryContext(
        val imageFiles: List<FileRef> = emptyList(),
        val textFiles: List<FileRef> = emptyList()
    )

    data class SidecarPayload(
        val description: String? = null,
        val coverFile: FileRef? = null
    )

    suspend fun resolveForManifest(
        manifestFile: FileRef,
        directoryContext: DirectoryContext,
        openTextFile: suspend (FileRef) -> InputStream?
    ): SidecarPayload {
        val baseName = manifestFile.displayName.substringBeforeLast('.', missingDelimiterValue = manifestFile.displayName)
        return SidecarPayload(
            description = readTxtDescription(
                textFiles = directoryContext.textFiles,
                openTextFile = openTextFile,
                baseName = baseName,
                strictSameNameOnly = false
            ),
            coverFile = findDirectoryCover(directoryContext.imageFiles)
        )
    }

    suspend fun resolveForHeuristic(
        directoryContext: DirectoryContext,
        openTextFile: suspend (FileRef) -> InputStream?
    ): SidecarPayload =
        SidecarPayload(
            // Heuristic Text Selection (No manifest anchor is available for same-name matching)
            // Standard description names or a single txt sibling remain eligible through ManifestSidecarSelectionPolicy.
            description = readTxtDescription(
                textFiles = directoryContext.textFiles,
                openTextFile = openTextFile,
                baseName = null,
                strictSameNameOnly = false
            ),
            coverFile = findDirectoryCover(directoryContext.imageFiles)
        )

    suspend fun readTxtDescription(
        textFiles: List<FileRef>,
        openTextFile: suspend (FileRef) -> InputStream?,
        baseName: String? = null,
        strictSameNameOnly: Boolean = false
    ): String? {
        val selectedTextFile = ManifestSidecarSelectionPolicy.selectTextDescription(
            textFiles = textFiles,
            baseName = baseName,
            strictSameNameOnly = strictSameNameOnly
        ) ?: return null
        return readTextFile(openTextFile, selectedTextFile)
    }

    fun findDirectoryCover(imageFiles: List<FileRef>): FileRef? =
        ManifestSidecarSelectionPolicy.selectDirectoryCover(imageFiles)

    private suspend fun readTextFile(
        openTextFile: suspend (FileRef) -> InputStream?,
        textFile: FileRef
    ): String? {
        var isTruncated = false
        val bytes = openTextFile(textFile)?.use { input ->
            BufferedInputStream(input).use { buffered ->
                val result = readLimitedBytes(buffered, MAX_DESCRIPTION_BYTES)
                if (result.size == MAX_DESCRIPTION_BYTES) {
                    buffered.mark(1)
                    if (buffered.read() != -1) {
                        isTruncated = true
                    }
                }
                result
            }
        } ?: return null

        val utf8Text = bytes.decodeUtf8PossiblyTruncated()
        val decoded = when {
            bytes.hasUtf8Bom() -> bytes.copyOfRange(3, bytes.size).decodeUtf8PossiblyTruncated() ?: ""
            utf8Text != null -> utf8Text
            bytes.isValidBig5() -> bytes.toString(Charset.forName("Big5"))
            else -> bytes.toString(Charset.forName("Shift-JIS"))
        }

        val finalIsTruncated = isTruncated || decoded.length > MAX_DESCRIPTION_CHARS
        val baseDescription = decoded.take(MAX_DESCRIPTION_CHARS)
        val normalized = if (finalIsTruncated) {
            "${baseDescription.trimEnd()}..."
        } else {
            baseDescription
        }.trim()

        return normalized.takeIf { it.isNotBlank() }
    }

    private fun readLimitedBytes(input: BufferedInputStream, limitBytes: Int): ByteArray {
        val buffer = ByteArray(limitBytes)
        var total = 0
        while (total < limitBytes) {
            val count = input.read(buffer, total, limitBytes - total)
            if (count == -1) break
            total += count
        }
        return buffer.copyOf(total)
    }

    private fun ByteArray.decodeUtf8PossiblyTruncated(): String? {
        if (isEmpty()) return ""
        for (endExclusive in size downTo maxOf(1, size - MAX_UTF8_CODE_POINT_BYTES + 1)) {
            val candidate = copyOfRange(0, endExclusive)
            if (candidate.isValidUtf8()) return candidate.toString(Charsets.UTF_8)
        }
        return null
    }

    private fun ByteArray.hasUtf8Bom(): Boolean =
        size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte()

    private fun ByteArray.isValidUtf8(): Boolean {
        var index = 0
        while (index < size) {
            val byte = this[index].toInt() and 0xFF
            if (byte < 0x80) {
                index++
                continue
            }
            val continuationCount = when (byte) {
                in 0xC2..0xDF -> 1
                in 0xE0..0xEF -> 2
                in 0xF0..0xF4 -> 3
                else -> return false
            }
            if (index + continuationCount >= size) return false
            for (offset in 1..continuationCount) {
                if ((this[index + offset].toInt() and 0xC0) != 0x80) return false
            }
            index += continuationCount + 1
        }
        return true
    }

    private fun ByteArray.isValidBig5(): Boolean {
        var index = 0
        var hasBig5Pair = false
        while (index < size) {
            val first = this[index].toInt() and 0xFF
            if (first <= 0x7F) {
                index++
                continue
            }
            if (first !in 0x81..0xFE || index + 1 >= size) return false
            val second = this[index + 1].toInt() and 0xFF
            if (second !in 0x40..0x7E && second !in 0xA1..0xFE) return false
            hasBig5Pair = true
            index += 2
        }
        return hasBig5Pair
    }

    private const val MAX_DESCRIPTION_BYTES = 10000
    private const val MAX_DESCRIPTION_CHARS = 2000
    private const val MAX_UTF8_CODE_POINT_BYTES = 4
}
