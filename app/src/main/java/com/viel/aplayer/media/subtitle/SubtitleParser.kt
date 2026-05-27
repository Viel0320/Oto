package com.viel.aplayer.media.subtitle

import android.util.Log
import com.viel.aplayer.ui.player.components.SubtitleLine
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale

/**
 * Utility for parsing SRT and ASS subtitle files.
 * Uses a streaming approach to handle large files (up to 10MB) efficiently.
 */
object SubtitleParser {

    /**
     * Parses a subtitle file based on its extension.
     * @param inputStream The stream of the subtitle file.
     * @param extension The file extension (srt, ass, ssa).
     * @return A list of parsed SubtitleLine objects.
     */
    fun parse(inputStream: InputStream, extension: String): List<SubtitleLine> {
        com.viel.aplayer.logger.SubtitleLogger.logParseStart(extension)
        return when (extension.lowercase(Locale.ROOT)) {
            "srt" -> parseSrt(inputStream)
            "ass", "ssa" -> parseAss(inputStream)
            "vtt" -> parseVtt(inputStream)
            "lrc" -> parseLrc(inputStream)
            else -> emptyList()
        }.also {
            com.viel.aplayer.logger.SubtitleLogger.logParseResult(it.size)
        }
    }

    /**
     * Parsing LRC (Lyrics) format.
     */
    private fun parseLrc(inputStream: InputStream): List<SubtitleLine> {
        val lines = mutableListOf<SubtitleLine>()
        val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
        val lrcList = mutableListOf<Pair<Long, String>>()

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line?.trim() ?: ""
                if (trimmed.isEmpty()) continue

                // LRC Format: [mm:ss.xx]Text or [mm:ss:xx]Text
                val regex = Regex("\\[(\\d{2}):(\\d{2})[.:](\\d{2,3})](.*)")
                val match = regex.find(trimmed)
                if (match != null) {
                    val min = match.groupValues[1].toLong()
                    val sec = match.groupValues[2].toLong()
                    val msPart = match.groupValues[3]
                    val ms = if (msPart.length == 2) msPart.toLong() * 10 else msPart.toLong()
                    val time = min * 60000 + sec * 1000 + ms
                    val text = match.groupValues[4].trim()
                    lrcList.add(time to text)
                }
            }

            // Convert point-in-time LRC to duration-based SubtitleLine
            for (i in 0 until lrcList.size) {
                val startTime = lrcList[i].first
                val endTime = if (i < lrcList.size - 1) lrcList[i + 1].first else startTime + 10000
                lines.add(SubtitleLine(startTime, endTime, lrcList[i].second))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { reader.close() } catch (_: Exception) {}
        }
        return lines
    }

    /**
     * Parsing VTT (WebVTT) format.
     * Simplified version, treats it similar to SRT.
     */
    private fun parseVtt(inputStream: InputStream): List<SubtitleLine> {
        val lines = mutableListOf<SubtitleLine>()
        val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))

        var line: String?
        var state = 0 // 0: Searching for Time, 1: Text
        var startTime = 0L
        var endTime = 0L
        val textBuilder = StringBuilder()

        try {
            // Skip "WEBVTT" header
            reader.readLine()

            while (reader.readLine().also { line = it } != null) {
                val trimmed = line?.trim() ?: ""

                // Time format: 00:00:20.000 --> 00:00:24.400
                if (trimmed.contains(" --> ")) {
                    if (textBuilder.isNotEmpty()) {
                        lines.add(SubtitleLine(startTime, endTime, textBuilder.toString().trim()))
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
                    if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                    textBuilder.append(trimmed)
                } else if (trimmed.isEmpty() && textBuilder.isNotEmpty()) {
                    lines.add(SubtitleLine(startTime, endTime, textBuilder.toString().trim()))
                    textBuilder.setLength(0)
                    state = 0
                }
            }
            if (textBuilder.isNotEmpty()) {
                lines.add(SubtitleLine(startTime, endTime, textBuilder.toString().trim()))
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
     * Parsing SRT (SubRip) format.
     */
    private fun parseSrt(inputStream: InputStream): List<SubtitleLine> {
        val lines = mutableListOf<SubtitleLine>()
        // Using BufferedReader for memory efficiency
        val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))

        var line: String?
        var state = 0 // 0: Index, 1: Time, 2: Text
        var startTime = 0L
        var endTime = 0L
        val textBuilder = StringBuilder()

        try {
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line?.trim() ?: ""
                if (trimmed.isEmpty()) {
                    if (textBuilder.isNotEmpty()) {
                        lines.add(SubtitleLine(startTime, endTime, textBuilder.toString().trim()))
                        textBuilder.setLength(0)
                    }
                    state = 0
                    continue
                }

                when (state) {
                    0 -> {
                        // Skip the index number, move to time
                        if (trimmed.toLongOrNull() != null) {
                            state = 1
                        }
                    }
                    1 -> {
                        // Time format: 00:00:20,000 --> 00:00:24,400
                        val times = trimmed.split(" --> ")
                        if (times.size == 2) {
                            startTime = parseSrtTime(times[0])
                            endTime = parseSrtTime(times[1])
                            state = 2
                        }
                    }
                    2 -> {
                        // Text lines
                        if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                        textBuilder.append(trimmed)
                    }
                }
            }
            // Add the last segment if the file doesn't end with an empty line
            if (textBuilder.isNotEmpty()) {
                lines.add(SubtitleLine(startTime, endTime, textBuilder.toString().trim()))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { reader.close() } catch (_: Exception) {}
        }
        return lines
    }

    private fun parseSrtTime(timeStr: String): Long {
        return try {
            // SRT uses comma as decimal separator: 00:00:00,000
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
     * Parsing ASS (Advanced Substation Alpha) format.
     */
    private fun parseAss(inputStream: InputStream): List<SubtitleLine> {
        val lines = mutableListOf<SubtitleLine>()
        val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
        var line: String?
        var inEvents = false
        var formatIndices: Map<String, Int>? = null

        try {
            while (reader.readLine().also { line = it } != null) {
                val rawLine = line ?: ""
                val trimmed = rawLine.trim()

                // We only care about the [Events] section
                if (trimmed.startsWith("[Events]", ignoreCase = true)) {
                    inEvents = true
                    continue
                }

                if (!inEvents) continue

                // Parse the format line to know column positions
                if (trimmed.startsWith("Format:", ignoreCase = true)) {
                    val formatParts = trimmed.substring(7).split(",").map { it.trim() }
                    formatIndices = formatParts.withIndex().associate { it.value to it.index }
                    continue
                }

                // Parse dialogue lines
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
                                lines.add(SubtitleLine(startTime, endTime, cleanedText))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { reader.close() } catch (_: Exception) {}
        }
        return lines
    }

    private fun parseAssTime(timeStr: String): Long {
        return try {
            // ASS time format: h:mm:ss.cc
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
            .replace(Regex("\\{.*?\\}"), "") // Remove {...} tags
            .replace("\\N", "\n")           // ASS newline
            .replace("\\n", "\n")
            .replace("\\h", " ")            // ASS hard space
            .trim()
    }
}