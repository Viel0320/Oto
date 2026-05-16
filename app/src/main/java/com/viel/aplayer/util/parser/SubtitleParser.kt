package com.viel.aplayer.util.parser

import android.util.Log
import com.viel.aplayer.ui.components.SubtitleLine
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
        Log.d("SubtitleParser", "Parsing $extension")
        return when (extension.lowercase(Locale.ROOT)) {
            "srt" -> parseSrt(inputStream)
            "ass", "ssa" -> parseAss(inputStream)
            else -> emptyList()
        }.also {
            Log.d("SubtitleParser", "Parsed ${it.size} lines")
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