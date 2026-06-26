package com.viel.oto.media.subtitle

import com.viel.oto.logger.SubtitleLogger
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale

/**
 * Caps parsed sidecar entries before they reach playback state.
 *
 * Keeps external subtitle files from expanding into unbounded cue lists that would inflate PlayerViewModel and Compose
 * state during active playback.
 */
private const val MAX_SUBTITLE_CUES = 10_000

/**
 * Caps individual cue text before it reaches playback rendering.
 *
 * Bounds malformed single cues or binary-like subtitle rows while preserving normal multi-line subtitle content.
 */
private const val MAX_SUBTITLE_TEXT_CHARS = 8_192

/**
 * Utility for parsing SRT and ASS subtitle files.
 * up to 10MB. efficiently.
 */
object SubtitleParser {

    /**
     * Parses a subtitle file based on its extension.
     * @param inputStream The stream of the subtitle file.
     * @param extension The file extension (srt, ass, ssa).
     * @return A list of parsed SubtitleLine objects.
     */
    fun parse(inputStream: InputStream, extension: String): List<SubtitleLine> {
        SubtitleLogger.logParseStart(extension)
        return when (extension.lowercase(Locale.ROOT)) {
            "srt" -> parseSrt(inputStream, extension)
            "ass", "ssa" -> parseAss(inputStream, extension)
            "vtt" -> parseVtt(inputStream, extension)
            "lrc" -> parseLrc(inputStream, extension)
            else -> emptyList()
        }.also {
            SubtitleLogger.logParseResult(it.size)
        }
    }

    /**
     * Keep subtitle payloads bounded before they enter Compose state.
     *
     * Shared parser and ViewModel callers use one policy so oversized sidecar files cannot allocate unbounded cue
     * lists or single-cue text blobs on the playback screen.
     */
    fun limitForPlayerState(lines: List<SubtitleLine>): List<SubtitleLine> {
        val limitedCueCount = if (lines.size > MAX_SUBTITLE_CUES) {
            lines.take(MAX_SUBTITLE_CUES)
        } else {
            lines
        }
        return limitedCueCount.map { line ->
            line.copy(text = line.text.limitSubtitleText())
        }
    }

    /**
     * Lyrics. format.
     */
    private fun parseLrc(inputStream: InputStream, extension: String): List<SubtitleLine> {
        val lines = mutableListOf<SubtitleLine>()
        val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
        val lrcList = mutableListOf<Pair<Long, String>>()

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line?.trim() ?: ""
                if (trimmed.isEmpty()) continue

                val regex = Regex("\\[(\\d{2}):(\\d{2})[.:](\\d{2,3})](.*)")
                val match = regex.find(trimmed)
                if (match != null) {
                    if (lrcList.size >= MAX_SUBTITLE_CUES) {
                        SubtitleLogger.logSubtitleTruncated(extension)
                        break
                    }
                    val min = match.groupValues[1].toLong()
                    val sec = match.groupValues[2].toLong()
                    val msPart = match.groupValues[3]
                    val ms = if (msPart.length == 2) msPart.toLong() * 10 else msPart.toLong()
                    val time = min * 60000 + sec * 1000 + ms
                    val text = match.groupValues[4].trim().limitSubtitleText()
                    lrcList.add(time to text)
                }
            }

            for ((i, element) in lrcList.withIndex()) {
                val startTime = element.first
                val endTime = if (i < lrcList.size - 1) lrcList[i + 1].first else startTime + 10000
                lines.add(SubtitleLine(startTime, endTime, element.second))
            }
        } catch (e: Exception) {
            /**
             * Handle Parsing Exception: Safe logs parsing error without insecure console prints in production.
             */
            SubtitleLogger.logParseError(extension, e)
        } finally {
            try { reader.close() } catch (_: Exception) {}
        }
        return lines
    }

    /**
     * WebVTT. format.
     * Simplified version, treats it similar to SRT.
     */
    private fun parseVtt(inputStream: InputStream, extension: String): List<SubtitleLine> {
        val lines = mutableListOf<SubtitleLine>()
        val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))

        var line: String?
        var state = 0
        var startTime = 0L
        var endTime = 0L
        val textBuilder = StringBuilder()

        try {
            reader.readLine()

            while (reader.readLine().also { line = it } != null) {
                val trimmed = line?.trim() ?: ""

                if (trimmed.contains(" --> ")) {
                    if (textBuilder.isNotEmpty()) {
                        if (!lines.addSubtitleLine(startTime, endTime, textBuilder.toString(), extension)) {
                            textBuilder.setLength(0)
                            break
                        }
                        textBuilder.setLength(0)
                    }
                    val times = trimmed.split(" --> ")
                    if (times.size == 2) {
                        startTime = parseVttTime(times[0])
                        endTime = parseVttTime(times[1])
                        state = 1
                    }
                    continue
                }

                if (state == 1 && trimmed.isNotEmpty()) {
                    textBuilder.appendSubtitleTextLine(trimmed)
                } else if (trimmed.isEmpty() && textBuilder.isNotEmpty()) {
                    if (!lines.addSubtitleLine(startTime, endTime, textBuilder.toString(), extension)) {
                        textBuilder.setLength(0)
                        break
                    }
                    textBuilder.setLength(0)
                    state = 0
                }
            }
            if (textBuilder.isNotEmpty()) {
                lines.addSubtitleLine(startTime, endTime, textBuilder.toString(), extension)
            }
        } catch (e: Exception) {
            /**
             * Handle Parsing Exception: Safe logs parsing error without insecure console prints in production.
             */
            SubtitleLogger.logParseError(extension, e)
        } finally {
            try { reader.close() } catch (_: Exception) {}
        }
        return lines
    }

    private fun parseVttTime(timeStr: String): Long {
        return try {
            val parts = timeStr.split(":")
            when (parts.size) {
                3 -> {
                    val hours = parts[0].toLong()
                    val minutes = parts[1].toLong()
                    val seconds = parts[2].replace(',', '.').toDouble()
                    ((hours * 3600 + minutes * 60 + seconds) * 1000).toLong()
                }
                2 -> {
                    val minutes = parts[0].toLong()
                    val seconds = parts[1].replace(',', '.').toDouble()
                    ((minutes * 60 + seconds) * 1000).toLong()
                }
                else -> 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * SubRip. format.
     */
    private fun parseSrt(inputStream: InputStream, extension: String): List<SubtitleLine> {
        val lines = mutableListOf<SubtitleLine>()
        val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))

        var line: String?
        var state = 0
        var startTime = 0L
        var endTime = 0L
        val textBuilder = StringBuilder()

        try {
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line?.trim() ?: ""
                if (trimmed.isEmpty()) {
                    if (textBuilder.isNotEmpty()) {
                        if (!lines.addSubtitleLine(startTime, endTime, textBuilder.toString(), extension)) {
                            textBuilder.setLength(0)
                            break
                        }
                        textBuilder.setLength(0)
                    }
                    state = 0
                    continue
                }

                when (state) {
                    0 -> {
                        if (trimmed.toLongOrNull() != null) {
                            state = 1
                        }
                    }
                    1 -> {
                        val times = trimmed.split(" --> ")
                        if (times.size == 2) {
                            startTime = parseSrtTime(times[0])
                            endTime = parseSrtTime(times[1])
                            state = 2
                        }
                    }
                    2 -> {
                        textBuilder.appendSubtitleTextLine(trimmed)
                    }
                }
            }
            if (textBuilder.isNotEmpty()) {
                lines.addSubtitleLine(startTime, endTime, textBuilder.toString(), extension)
            }
        } catch (e: Exception) {
            /**
             * Handle Parsing Exception: Safe logs parsing error without insecure console prints in production.
             */
            SubtitleLogger.logParseError(extension, e)
        } finally {
            try { reader.close() } catch (_: Exception) {}
        }
        return lines
    }

    private fun parseSrtTime(timeStr: String): Long {
        return try {
            val parts = timeStr.replace(',', '.').split(":")
            val hours = parts[0].toLong()
            val minutes = parts[1].toLong()
            val seconds = parts[2].toDouble()
            ((hours * 3600 + minutes * 60 + seconds) * 1000).toLong()
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Advanced Substation Alpha. format.
     */
    private fun parseAss(inputStream: InputStream, extension: String): List<SubtitleLine> {
        val lines = mutableListOf<SubtitleLine>()
        val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
        var line: String?
        var inEvents = false
        var formatIndices: Map<String, Int>? = null

        try {
            while (reader.readLine().also { line = it } != null) {
                val rawLine = line ?: ""
                val trimmed = rawLine.trim()

                if (trimmed.startsWith("[Events]", ignoreCase = true)) {
                    inEvents = true
                    continue
                }

                if (!inEvents) continue

                if (trimmed.startsWith("Format:", ignoreCase = true)) {
                    val formatParts = trimmed.substring(7).split(",").map { it.trim() }
                    formatIndices = formatParts.withIndex().associate { it.value to it.index }
                    continue
                }

                if (trimmed.startsWith("Dialogue:", ignoreCase = true) && formatIndices != null) {
                    val data = rawLine.substring(9).split(",", limit = formatIndices.size)
                    if (data.size >= formatIndices.size) {
                        val startIdx = formatIndices["Start"] ?: -1
                        val endIdx = formatIndices["End"] ?: -1
                        val textIdx = formatIndices["Text"] ?: -1

                        if (startIdx != -1 && endIdx != -1 && textIdx != -1) {
                            val startTime = parseAssTime(data[startIdx].trim())
                            val endTime = parseAssTime(data[endIdx].trim())
                            val cleanedText = cleanAssText(data[textIdx].trim())

                            if (cleanedText.isNotEmpty()) {
                                if (!lines.addSubtitleLine(startTime, endTime, cleanedText, extension)) {
                                    break
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            /**
             * Handle Parsing Exception: Safe logs parsing error without insecure console prints in production.
             */
            SubtitleLogger.logParseError(extension, e)
        } finally {
            try { reader.close() } catch (_: Exception) {}
        }
        return lines
    }

    private fun parseAssTime(timeStr: String): Long {
        return try {
            val parts = timeStr.split(":")
            val hours = parts[0].toLong()
            val minutes = parts[1].toLong()
            val seconds = parts[2].toDouble()
            ((hours * 3600 + minutes * 60 + seconds) * 1000).toLong()
        } catch (_: Exception) {
            0L
        }
    }

    private fun cleanAssText(text: String): String {
        return text
            .replace(Regex("\\{.*?\\}"), "")
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .replace("\\h", " ")
            .trim()
    }

    /**
     * Stop parsing before oversized subtitle files inflate player state.
     *
     * Adds one cue only when the shared cue budget still has capacity, and trims text to keep individual malformed
     * cues from becoming large Compose text payloads.
     */
    private fun MutableList<SubtitleLine>.addSubtitleLine(
        startTime: Long,
        endTime: Long,
        text: String,
        extension: String
    ): Boolean {
        if (size >= MAX_SUBTITLE_CUES) {
            SubtitleLogger.logSubtitleTruncated(extension)
            return false
        }
        val limitedText = text.trim().limitSubtitleText()
        if (limitedText.isNotEmpty()) {
            add(SubtitleLine(startTime, endTime, limitedText))
        }
        return true
    }

    /**
     * Trim pathological cue text before it reaches playback UI state.
     *
     * Keeps valid cue content intact within the budget while bounding malformed or binary-like subtitle rows.
     */
    private fun String.limitSubtitleText(): String =
        if (length > MAX_SUBTITLE_TEXT_CHARS) {
            take(MAX_SUBTITLE_TEXT_CHARS)
        } else {
            this
        }

    /**
     * Avoid accumulating oversized multi-line cue text in parser buffers.
     *
     * Appends only the remaining text capacity so SRT and VTT cues are bounded while they are being read, not merely
     * after the cue has already been assembled.
     */
    private fun StringBuilder.appendSubtitleTextLine(text: String) {
        if (length >= MAX_SUBTITLE_TEXT_CHARS) return
        appendSubtitleTextPart(if (isNotEmpty()) "\n" else "")
        appendSubtitleTextPart(text)
    }

    /**
     * Clamp incremental parser appends to the per-cue text allowance.
     *
     * Centralizes the remaining-capacity calculation so multiline subtitles cannot overrun the shared text budget.
     */
    private fun StringBuilder.appendSubtitleTextPart(text: String) {
        val remainingTextCapacity = MAX_SUBTITLE_TEXT_CHARS - length
        if (remainingTextCapacity > 0) {
            append(text.take(remainingTextCapacity))
        }
    }
}
