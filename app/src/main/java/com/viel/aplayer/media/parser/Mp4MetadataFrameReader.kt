package com.viel.aplayer.media.parser

import android.os.SystemClock
import android.util.Log
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.media.AudiobookMetadata
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID

// MP4/M4B metadata frame reader only reads moov/ilst/covr atoms via VFS range small snippets, avoiding full remote file downloads.
object Mp4MetadataFrameReader {
    data class EmbeddedCover(
        val bytes: ByteArray,
        val mimeType: String?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as EmbeddedCover

            if (!bytes.contentEquals(other.bytes)) return false
            if (mimeType != other.mimeType) return false

            return true
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + (mimeType?.hashCode() ?: 0)
            return result
        }
    }

    data class Result(
        val metadata: AudiobookMetadata,
        val cover: EmbeddedCover?
    )

    private data class Atom(
        val offset: Long,
        val size: Long,
        val type: String,
        val headerSize: Int
    ) {
        val contentOffset: Long get() = offset + headerSize
        val endOffset: Long get() = offset + size
    }

    private data class IlstData(
        val textValues: Map<String, String>,
        val trackIndex: Int?,
        val cover: EmbeddedCover?
    )

    private data class ReadOptions(
        val includeMetadataFields: Boolean,
        val includeCover: Boolean,
        val includeChapters: Boolean,
        val includeDuration: Boolean,
        val purpose: String
    )

    private data class TrackInfo(
        val id: Int,
        val atom: Atom,
        val chapterTrackIds: List<Int>
    )

    private data class MediaHeader(
        val timescale: Long,
        val durationMs: Long
    )

    private data class StscEntry(
        val firstChunk: Int,
        val samplesPerChunk: Int
    )

    fun supports(displayName: String): Boolean {
        // Exposes MP4 family file extensions to make both local and remote m4b/m4a/mp4 formats use atom frame parsing.
        return displayName.isMp4FamilyName()
    }

    suspend fun extract(file: FileRef, fileReader: VfsFileInterface): Result? {
        if (!supports(file.displayName)) return null
        return extractWithRangeReader(
            displayName = file.displayName,
            sourceId = file.vfsKey,
            fileSize = file.fileSize,
            readRange = { offset, length -> fileReader.readRange(file, offset, length) },
            options = FULL_READ_OPTIONS
        )
    }

    suspend fun extract(file: BookFileEntity, fileReader: VfsFileInterface): Result? {
        if (!supports(file.displayName)) return null
        return extractWithRangeReader(
            displayName = file.displayName,
            sourceId = "${file.rootId}:${file.sourcePath}",
            fileSize = file.fileSize,
            readRange = { offset, length -> fileReader.readRange(file, offset, length) },
            options = FULL_READ_OPTIONS
        )
    }

    suspend fun extractMetadata(file: FileRef, fileReader: VfsFileInterface): AudiobookMetadata? {
        if (!supports(file.displayName)) return null
        // Retains a metadata-only entry point for non-import scenarios to avoid pulling covr image bytes into memory.
        return extractWithRangeReader(
            displayName = file.displayName,
            sourceId = file.vfsKey,
            fileSize = file.fileSize,
            readRange = { offset, length -> fileReader.readRange(file, offset, length) },
            options = METADATA_ONLY_OPTIONS
        )?.metadata
    }

    suspend fun extractMetadata(file: BookFileEntity, fileReader: VfsFileInterface): AudiobookMetadata? {
        if (!supports(file.displayName)) return null
        // Rebuilding metadata for imported files defaults to reading lightweight tags/chapters; cover caching is handled by the recovery flow.
        return extractWithRangeReader(
            displayName = file.displayName,
            sourceId = "${file.rootId}:${file.sourcePath}",
            fileSize = file.fileSize,
            readRange = { offset, length -> fileReader.readRange(file, offset, length) },
            options = METADATA_ONLY_OPTIONS
        )?.metadata
    }

    suspend fun extractMetadataResult(file: FileRef, fileReader: VfsFileInterface): Result? {
        if (!supports(file.displayName)) return null
        // Synchronously reads covr during metadata flow for evaluation, without taking over the cover recovery path, logging execution metrics.
        return extractWithRangeReader(
            displayName = file.displayName,
            sourceId = file.vfsKey,
            fileSize = file.fileSize,
            readRange = { offset, length -> fileReader.readRange(file, offset, length) },
            options = METADATA_WITH_COVER_OPTIONS
        )
    }

    suspend fun extractMetadataResult(file: BookFileEntity, fileReader: VfsFileInterface): Result? {
        if (!supports(file.displayName)) return null
        // Rebuilding metadata for imported files temporarily reads covr synchronously for performance evaluation without writing cache.
        return extractWithRangeReader(
            displayName = file.displayName,
            sourceId = "${file.rootId}:${file.sourcePath}",
            fileSize = file.fileSize,
            readRange = { offset, length -> fileReader.readRange(file, offset, length) },
            options = METADATA_WITH_COVER_OPTIONS
        )
    }

    suspend fun extractCover(file: FileRef, fileReader: VfsFileInterface): EmbeddedCover? {
        if (!supports(file.displayName)) return null
        // Cover recovery only reads ilst/covr and skips parsing the chapter table, avoiding I/O contention between cover self-healing and metadata import.
        return extractWithRangeReader(
            displayName = file.displayName,
            sourceId = file.vfsKey,
            fileSize = file.fileSize,
            readRange = { offset, length -> fileReader.readRange(file, offset, length) },
            options = COVER_ONLY_OPTIONS
        )?.cover
    }

    suspend fun extractCover(file: BookFileEntity, fileReader: VfsFileInterface): EmbeddedCover? {
        if (!supports(file.displayName)) return null
        // Cover rebuilding for imported files also reads cover-only atoms, avoiding duplicate parsing of chapters and text metadata.
        return extractWithRangeReader(
            displayName = file.displayName,
            sourceId = "${file.rootId}:${file.sourcePath}",
            fileSize = file.fileSize,
            readRange = { offset, length -> fileReader.readRange(file, offset, length) },
            options = COVER_ONLY_OPTIONS
        )?.cover
    }

    private suspend fun extractWithRangeReader(
        displayName: String,
        sourceId: String,
        fileSize: Long,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?,
        options: ReadOptions
    ): Result? {
        // MP4 metadata and cover parsing completely deprecate the local direct read fast-path; both SAF and WebDAV route through VFS readRange.
        val stats = RangeReadStats(mode = "vfs-range")
        val rangeReader = CoalescingRangeReader(fileSize, stats, readRange)
        val startedAt = SystemClock.elapsedRealtime()
        val result = extract(displayName, sourceId, fileSize, options) { offset, length ->
            rangeReader.read(offset, length)
        }
        logRangeStats(sourceId, stats, startedAt, result != null, options)
        return result
    }

    private suspend fun extract(
        displayName: String,
        sourceId: String,
        fileSize: Long,
        options: ReadOptions,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?
    ): Result? {
        if (fileSize <= 0L) return null
        val moov = findAtom(0L, fileSize, fileSize, "moov", readRange) ?: return null
        val durationMs = if (options.includeDuration) {
            findChildAtom(moov, fileSize, "mvhd", readRange)?.let { parseMvhd(it, readRange) } ?: 0L
        } else {
            0L
        }
        val udta = findChildAtom(moov, fileSize, "udta", readRange)
        val ilst = udta
            ?.let { findChildAtom(it, fileSize, "meta", readRange) }
            ?.let { meta -> findChildAtom(meta, fileSize, "ilst", readRange, extraHeaderBytes = 4) }
        val ilstData = ilst?.let { parseIlst(it, readRange, options) } ?: IlstData(emptyMap(), null, null)
        val chapters = if (options.includeChapters) {
            val neroChapters = udta
                ?.let { findChildAtom(it, fileSize, "chpl", readRange) }
                ?.let { parseChpl(it, sourceId, readRange) }
                .orEmpty()
            neroChapters.ifEmpty {
                // Fall back to parsing chapters via QuickTime chapter track's stbl metadata table if Nero chpl is absent.
                parseQuickTimeChapters(moov, fileSize, sourceId, readRange)
            }.normalizedChapterDurations(durationMs)
        } else {
            emptyList()
        }

        val title = ilstData.textValues.firstText("\u00A9nam").ifBlank { displayName.substringBeforeLast('.') }
        val album = ilstData.textValues.firstText("\u00A9alb")
        val author = ilstData.textValues.firstText("\u00A9ART", "aART")
        val narrator = ilstData.textValues.firstText("\u00A9nrt", "\u00A9wrt", "\u00A9com")
        // MP4/M4B synopsis sources are inconsistent across tagging tools.
        // Prefers freeform custom fields which tend to be manually curated.
        // If absent, falls back to common comments and standard descriptions in fallbackKeys.
        // Adjusted match order in fallbackKeys, placing cmt series before des series to prioritize cmt fields over des.
        val description = MetadataDescriptionRules.firstDescriptionFromCustomAndFallback(
            values = ilstData.textValues,
            customPrefix = "----",
            fallbackKeys = listOf(
                "\u00A9cmt", // 标准 MP4 comment 字段 (©cmt)
                "cmt",       // 简写 cmt 备注字段
                "cmmt",      // cmmt 备注字段
                "desc",      // 简写 desc 简介字段
                "ldes",      // ldes 长简介字段
                "\u00A9des"  // 标准 MP4 description 字段 (©des)
            )
        )
        val rawYear = ilstData.textValues.firstText("\u00A9day")
        val year = Regex("\\d{4}").find(rawYear)?.value ?: rawYear

        // Even if only mvhd duration is extracted, it indicates successful range atom parsing, providing lightweight fallback metadata.
        return Result(
            metadata = AudiobookMetadata(
                title = title,
                author = author,
                narrator = narrator,
                album = album,
                trackIndex = ilstData.trackIndex,
                description = description,
                year = year,
                durationMs = durationMs,
                chapters = chapters
            ),
            cover = ilstData.cover
        )
    }

    private suspend fun parseIlst(
        ilst: Atom,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?,
        options: ReadOptions
    ): IlstData {
        val textValues = linkedMapOf<String, String>()
        var trackIndex: Int? = null
        var cover: EmbeddedCover? = null
        var pos = ilst.contentOffset
        while (pos + ATOM_HEADER_SIZE <= ilst.endOffset) {
            val item = readAtom(pos, ilst.endOffset, readRange) ?: break
            // iTunes freeform metadata uses top-level "----" atoms; field names are split into mean/name/data sub-atoms.
            // Values must be written to textValues after scanning all sub-atoms of the item to resolve the correct field name.
            var freeformMean: String? = null
            var freeformName: String? = null
            var freeformText: String? = null
            var childPos = item.contentOffset
            while (childPos + ATOM_HEADER_SIZE <= item.endOffset) {
                val child = readAtom(childPos, item.endOffset, readRange) ?: break
                if (item.type == "----" && options.includeMetadataFields) {
                    val dataLength = (child.size - child.headerSize).coerceAtMost(MAX_TEXT_DATA_BYTES.toLong()).toInt()
                    val payload = readRange(child.contentOffset, dataLength) ?: ByteArray(0)
                    when (child.type) {
                        "mean" -> freeformMean = parseFreeformLabel(payload)
                        "name" -> freeformName = parseFreeformLabel(payload)
                        "data" -> freeformText = parseTextData(payload)
                    }
                } else if (child.type == "data") {
                    when (item.type) {
                        "covr" -> if (options.includeCover && cover == null) {
                            val dataLength = (child.size - child.headerSize).coerceAtMost(MAX_COVER_BYTES.toLong()).toInt()
                            // Only the cover-specific path reads the large covr payload; standard metadata scanning must bypass it.
                            val payload = readRange(child.contentOffset, dataLength) ?: ByteArray(0)
                            cover = parseCoverData(payload)
                        }
                        "trkn" -> if (options.includeMetadataFields && trackIndex == null) {
                            val dataLength = (child.size - child.headerSize).coerceAtMost(MAX_TEXT_DATA_BYTES.toLong()).toInt()
                            // Track number is a lightweight metadata field; reads data payload with a small upper limit.
                            val payload = readRange(child.contentOffset, dataLength) ?: ByteArray(0)
                            trackIndex = parseTrackIndex(payload)
                        }
                        else -> if (options.includeMetadataFields) {
                            val dataLength = (child.size - child.headerSize).coerceAtMost(MAX_TEXT_DATA_BYTES.toLong()).toInt()
                            // Text metadata path only reads short data payloads, avoiding cover image bytes.
                            val payload = readRange(child.contentOffset, dataLength) ?: ByteArray(0)
                            parseTextData(payload)?.let { value -> textValues[item.type] = value }
                        }
                    }
                }
                childPos = child.nextOffsetOrBreak(childPos)
            }
            putFreeformTextValue(textValues, freeformMean, freeformName, freeformText)
            pos = item.nextOffsetOrBreak(pos)
        }
        return IlstData(textValues, trackIndex, cover)
    }

    private fun parseTextData(payload: ByteArray): String? {
        if (payload.size <= DATA_HEADER_SIZE) return null
        val textBytes = payload.copyOfRange(DATA_HEADER_SIZE, payload.size)
        return String(textBytes, StandardCharsets.UTF_8)
            .trim { it <= ' ' }
            .takeIf { it.isNotBlank() }
    }

    private fun parseFreeformLabel(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        // The mean/name sub-atoms usually start with a 4-byte version/flags header; some non-standard writers may omit it.
        // Tries both offset variations to ensure custom DESCRIPTION/COMMENT fields are not missed due to header discrepancies.
        return listOf(FREEFORM_LABEL_HEADER_BYTES, 0)
            .asSequence()
            .filter { start -> start < payload.size }
            .map { start ->
                String(payload.copyOfRange(start, payload.size), StandardCharsets.UTF_8)
                    .trim('\u0000', ' ', '\n', '\r', '\t')
            }
            .firstOrNull { value -> value.isNotBlank() }
    }

    private fun putFreeformTextValue(
        textValues: MutableMap<String, String>,
        mean: String?,
        name: String?,
        value: String?
    ) {
        val normalizedName = name?.let(MetadataDescriptionRules::normalizedFieldKey).orEmpty()
        val normalizedValue = value?.trim().orEmpty()
        if (normalizedName.isBlank() || normalizedValue.isBlank()) return

        // Description retrieval depends only on the field name; mean is retained as an auxiliary key for future source differentiation.
        textValues.putIfAbsent("----:$normalizedName", normalizedValue)
        mean?.let(MetadataDescriptionRules::normalizedFieldKey)
            ?.takeIf { it.isNotBlank() }
            ?.let { normalizedMean ->
                textValues.putIfAbsent("----:$normalizedMean:$normalizedName", normalizedValue)
            }
    }

    private fun parseCoverData(payload: ByteArray): EmbeddedCover? {
        if (payload.size <= DATA_HEADER_SIZE) return null
        val dataType = ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.BIG_ENDIAN).int and 0x00ffffff
        val mimeType = when (dataType) {
            13 -> "image/jpeg"
            14 -> "image/png"
            else -> null
        }
        val bytes = payload.copyOfRange(DATA_HEADER_SIZE, payload.size)
        return bytes.takeIf { it.isNotEmpty() }?.let { EmbeddedCover(it, mimeType) }
    }

    private fun parseTrackIndex(payload: ByteArray): Int? {
        if (payload.size <= DATA_HEADER_SIZE + 3) return null
        val content = payload.copyOfRange(DATA_HEADER_SIZE, payload.size)
        val buffer = ByteBuffer.wrap(content).order(ByteOrder.BIG_ENDIAN)
        return when {
            content.size >= 4 -> buffer.getShort(2).toInt().takeIf { it > 0 }
            content.size >= 2 -> buffer.short.toInt().takeIf { it > 0 }
            else -> null
        }
    }

    private suspend fun parseMvhd(
        mvhd: Atom,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?
    ): Long {
        val payload = readRange(mvhd.contentOffset, (mvhd.size - mvhd.headerSize).coerceAtMost(64).toInt()) ?: return 0L
        if (payload.size < 20) return 0L
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get(0).toInt()
        return runCatching {
            if (version == 1) {
                if (payload.size < 32) return@runCatching 0L
                val timescale = buffer.getInt(20).toLong()
                val duration = buffer.getLong(24)
                durationToMs(duration, timescale)
            } else {
                val timescale = buffer.getInt(12).toLong()
                val duration = buffer.getInt(16).toLong() and 0xffffffffL
                durationToMs(duration, timescale)
            }
        }.getOrDefault(0L)
    }

    private suspend fun parseChpl(
        chpl: Atom,
        sourceId: String,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?
    ): List<ChapterEntity> {
        val payloadSize = (chpl.size - chpl.headerSize).coerceAtMost(MAX_CHPL_BYTES.toLong()).toInt()
        val payload = readRange(chpl.contentOffset, payloadSize) ?: return emptyList()
        if (payload.size < 9) return emptyList()
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        buffer.position(8)
        val count = buffer.get().toInt() and 0xff
        val chapters = mutableListOf<ChapterEntity>()
        repeat(count) {
            if (buffer.remaining() < 9) return@repeat
            val startMs = buffer.long / 10000L
            val titleLength = buffer.get().toInt() and 0xff
            if (buffer.remaining() < titleLength) return@repeat
            val titleBytes = ByteArray(titleLength)
            buffer.get(titleBytes)
            chapters.add(
                ChapterEntity(
                    id = UUID.randomUUID().toString(),
                    bookId = "TEMP",
                    bookFileId = "",
                    index = chapters.size,
                    title = String(titleBytes, StandardCharsets.UTF_8).ifBlank { "Chapter ${chapters.size + 1}" },
                    startPositionMs = startMs,
                    durationMs = 0L,
                    fileOffsetMs = startMs,
                    // Update Mp4MetadataFrameReader to use AudiobookSchema.ChapterSource.EMBEDDED: Replacing raw string "EMBEDDED" with type-safe AudiobookSchema.ChapterSource.EMBEDDED enum.
                    source = AudiobookSchema.ChapterSource.EMBEDDED
                )
            )
        }
        for (index in 0 until chapters.lastIndex) {
            val current = chapters[index]
            chapters[index] = current.copy(durationMs = chapters[index + 1].startPositionMs - current.startPositionMs)
        }
        return chapters.distinctBy { it.startPositionMs }.sortedBy { it.startPositionMs }
    }

    private suspend fun parseQuickTimeChapters(
        moov: Atom,
        fileSize: Long,
        sourceId: String,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?
    ): List<ChapterEntity> {
        // QuickTime chapters map to a text track via tref/chap; parses only track metadata tables and small text samples.
        val tracks = findChildAtoms(moov, fileSize, "trak", readRange).mapNotNull { trak ->
            val tkhd = findChildAtom(trak, fileSize, "tkhd", readRange) ?: return@mapNotNull null
            val trackId = parseTkhdTrackId(tkhd, readRange) ?: return@mapNotNull null
            val chapterTrackIds = findChildAtom(trak, fileSize, "tref", readRange)
                ?.let { tref -> findChildAtom(tref, fileSize, "chap", readRange) }
                ?.let { chap -> parseChapterTrackReferences(chap, readRange) }
                .orEmpty()
            TrackInfo(trackId, trak, chapterTrackIds)
        }
        val chapterTrackId = tracks.firstNotNullOfOrNull { track ->
            track.chapterTrackIds.firstOrNull { id -> tracks.any { candidate -> candidate.id == id } }
        } ?: return emptyList()
        val chapterTrack = tracks.firstOrNull { it.id == chapterTrackId }?.atom ?: return emptyList()
        return parseChapterTextTrack(chapterTrack, fileSize, sourceId, readRange)
    }

    private suspend fun parseChapterTextTrack(
        trak: Atom,
        fileSize: Long,
        sourceId: String,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?
    ): List<ChapterEntity> {
        val mdia = findChildAtom(trak, fileSize, "mdia", readRange) ?: return emptyList()
        val mediaHeader = findChildAtom(mdia, fileSize, "mdhd", readRange)?.let { parseMdhd(it, readRange) } ?: return emptyList()
        if (mediaHeader.timescale <= 0L) return emptyList()
        val minf = findChildAtom(mdia, fileSize, "minf", readRange) ?: return emptyList()
        val stbl = findChildAtom(minf, fileSize, "stbl", readRange) ?: return emptyList()
        val sampleDurations = findChildAtom(stbl, fileSize, "stts", readRange)
            ?.let { parseSttsDurations(it, readRange) }
            .orEmpty()
        if (sampleDurations.isEmpty()) return emptyList()
        val sampleSizes = findChildAtom(stbl, fileSize, "stsz", readRange)
            ?.let { parseStszSizes(it, readRange) }
            .orEmpty()
        if (sampleSizes.isEmpty()) return emptyList()
        val chunkOffsets = findChildAtom(stbl, fileSize, "stco", readRange)
            ?.let { parseStcoOffsets(it, readRange) }
            ?: findChildAtom(stbl, fileSize, "co64", readRange)
                ?.let { parseCo64Offsets(it, readRange) }
            ?: emptyList()
        if (chunkOffsets.isEmpty()) return emptyList()
        val sampleToChunk = findChildAtom(stbl, fileSize, "stsc", readRange)
            ?.let { parseStscEntries(it, readRange) }
            .orEmpty()
        val sampleOffsets = buildSampleOffsets(chunkOffsets, sampleSizes, sampleToChunk)
        val sampleCount = minOf(sampleOffsets.size, sampleSizes.size, sampleDurations.size.takeIf { it > 0 } ?: sampleSizes.size)
        if (sampleCount <= 0) return emptyList()

        val chapters = mutableListOf<ChapterEntity>()
        var currentTime = 0L
        for (index in 0 until sampleCount) {
            val size = sampleSizes[index]
            val title = readQuickTimeChapterTitle(sampleOffsets[index], size, readRange)
            val startMs = durationToMs(currentTime, mediaHeader.timescale)
            if (!title.isNullOrBlank()) {
                chapters.add(
                    ChapterEntity(
                        id = UUID.randomUUID().toString(),
                        bookId = "TEMP",
                        bookFileId = "",
                        index = chapters.size,
                        title = title,
                        startPositionMs = startMs,
                        durationMs = 0L,
                        fileOffsetMs = startMs,
                        // Update Mp4MetadataFrameReader to use AudiobookSchema.ChapterSource.EMBEDDED: Replacing raw string "EMBEDDED" with type-safe AudiobookSchema.ChapterSource.EMBEDDED enum.
                        source = AudiobookSchema.ChapterSource.EMBEDDED
                    )
                )
            }
            currentTime += sampleDurations.getOrNull(index) ?: 0L
        }
        val totalDurationMs = durationToMs(currentTime, mediaHeader.timescale).takeIf { it > 0L } ?: mediaHeader.durationMs
        return chapters.normalizedChapterDurations(totalDurationMs)
    }

    private suspend fun parseTkhdTrackId(
        tkhd: Atom,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?
    ): Int? {
        val payload = readRange(tkhd.contentOffset, (tkhd.size - tkhd.headerSize).coerceAtMost(32).toInt()) ?: return null
        if (payload.size < 16) return null
        val version = payload[0].toInt()
        val position = if (version == 1) 20 else 12
        if (payload.size < position + 4) return null
        return ByteBuffer.wrap(payload, position, 4).order(ByteOrder.BIG_ENDIAN).int.takeIf { it > 0 }
    }

    private suspend fun parseChapterTrackReferences(
        chap: Atom,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?
    ): List<Int> {
        val payloadSize = (chap.size - chap.headerSize).coerceAtMost((MAX_CHAPTER_TRACK_REFERENCES * 4).toLong()).toInt()
        val payload = readRange(chap.contentOffset, payloadSize) ?: return emptyList()
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val ids = mutableListOf<Int>()
        while (buffer.remaining() >= 4 && ids.size < MAX_CHAPTER_TRACK_REFERENCES) {
            buffer.int.takeIf { it > 0 }?.let { ids.add(it) }
        }
        return ids
    }

    private suspend fun parseMdhd(
        mdhd: Atom,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?
    ): MediaHeader? {
        val payload = readRange(mdhd.contentOffset, (mdhd.size - mdhd.headerSize).coerceAtMost(40).toInt()) ?: return null
        if (payload.size < 20) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get(0).toInt()
        return if (version == 1) {
            if (payload.size < 32) return null
            val timescale = buffer.getInt(20).toLong()
            val duration = buffer.getLong(24)
            MediaHeader(timescale = timescale, durationMs = durationToMs(duration, timescale))
        } else {
            val timescale = buffer.getInt(12).toLong()
            val duration = buffer.getInt(16).toLong() and 0xffffffffL
            MediaHeader(timescale = timescale, durationMs = durationToMs(duration, timescale))
        }
    }

    private suspend fun parseSttsDurations(
        stts: Atom,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?
    ): List<Long> {
        val payload = readFullAtomPayload(stts, readRange) ?: return emptyList()
        if (payload.size < 8) return emptyList()
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        val entryCount = (buffer.int.toLong() and 0xffffffffL).coerceAtMost(MAX_TABLE_ENTRIES.toLong()).toInt()
        val durations = mutableListOf<Long>()
        repeat(entryCount) {
            if (buffer.remaining() < 8 || durations.size >= MAX_CHAPTER_SAMPLES) return@repeat
            val count = buffer.int.toLong() and 0xffffffffL
            val delta = buffer.int.toLong() and 0xffffffffL
            val safeCount = count.coerceAtMost((MAX_CHAPTER_SAMPLES - durations.size).toLong()).toInt()
            repeat(safeCount) { durations.add(delta) }
        }
        return durations
    }

    private suspend fun parseStszSizes(
        stsz: Atom,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?
    ): List<Int> {
        val payload = readFullAtomPayload(stsz, readRange) ?: return emptyList()
        if (payload.size < 12) return emptyList()
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        val defaultSize = buffer.int.toLong() and 0xffffffffL
        val sampleCount = (buffer.int.toLong() and 0xffffffffL).coerceAtMost(MAX_CHAPTER_SAMPLES.toLong()).toInt()
        return if (defaultSize > 0L && defaultSize <= Int.MAX_VALUE) {
            List(sampleCount) { defaultSize.toInt() }
        } else {
            val sizes = mutableListOf<Int>()
            repeat(sampleCount) {
                if (buffer.remaining() < 4) return@repeat
                val size = buffer.int.toLong() and 0xffffffffL
                sizes.add(size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
            sizes
        }
    }

    private suspend fun parseStcoOffsets(
        stco: Atom,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?
    ): List<Long> {
        val payload = readFullAtomPayload(stco, readRange) ?: return emptyList()
        if (payload.size < 8) return emptyList()
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        val count = (buffer.int.toLong() and 0xffffffffL).coerceAtMost(MAX_CHAPTER_SAMPLES.toLong()).toInt()
        val offsets = mutableListOf<Long>()
        repeat(count) {
            if (buffer.remaining() < 4) return@repeat
            offsets.add(buffer.int.toLong() and 0xffffffffL)
        }
        return offsets
    }

    private suspend fun parseCo64Offsets(
        co64: Atom,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?
    ): List<Long> {
        val payload = readFullAtomPayload(co64, readRange) ?: return emptyList()
        if (payload.size < 8) return emptyList()
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        val count = (buffer.int.toLong() and 0xffffffffL).coerceAtMost(MAX_CHAPTER_SAMPLES.toLong()).toInt()
        val offsets = mutableListOf<Long>()
        repeat(count) {
            if (buffer.remaining() < 8) return@repeat
            offsets.add(buffer.long)
        }
        return offsets
    }

    private suspend fun parseStscEntries(
        stsc: Atom,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?
    ): List<StscEntry> {
        val payload = readFullAtomPayload(stsc, readRange) ?: return emptyList()
        if (payload.size < 8) return emptyList()
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        val count = (buffer.int.toLong() and 0xffffffffL).coerceAtMost(MAX_TABLE_ENTRIES.toLong()).toInt()
        val entries = mutableListOf<StscEntry>()
        repeat(count) {
            if (buffer.remaining() < 12) return@repeat
            val firstChunk = buffer.int
            val samplesPerChunk = buffer.int
            buffer.int // sample_description_index
            if (firstChunk > 0 && samplesPerChunk > 0) {
                entries.add(StscEntry(firstChunk, samplesPerChunk))
            }
        }
        return entries.sortedBy { it.firstChunk }
    }

    private fun buildSampleOffsets(
        chunkOffsets: List<Long>,
        sampleSizes: List<Int>,
        sampleToChunk: List<StscEntry>
    ): List<Long> {
        // Computes real offsets of text samples using stsc/stco/stsz combinations, avoiding treating chunks as samples.
        if (sampleToChunk.isEmpty()) {
            return chunkOffsets.take(sampleSizes.size)
        }
        val offsets = mutableListOf<Long>()
        var sampleIndex = 0
        var stscIndex = 0
        for (chunkIndex in chunkOffsets.indices) {
            val chunkNumber = chunkIndex + 1
            while (stscIndex + 1 < sampleToChunk.size && sampleToChunk[stscIndex + 1].firstChunk <= chunkNumber) {
                stscIndex++
            }
            var sampleOffset = chunkOffsets[chunkIndex]
            repeat(sampleToChunk[stscIndex].samplesPerChunk) {
                if (sampleIndex >= sampleSizes.size || offsets.size >= MAX_CHAPTER_SAMPLES) return offsets
                offsets.add(sampleOffset)
                sampleOffset += sampleSizes[sampleIndex].coerceAtLeast(0)
                sampleIndex++
            }
        }
        return offsets
    }

    private suspend fun readQuickTimeChapterTitle(
        offset: Long,
        size: Int,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?
    ): String? {
        if (size !in 3..MAX_CHAPTER_TEXT_BYTES) return null
        val sample = readRange(offset, size) ?: return null
        if (sample.size <= 2) return null
        val declaredLength = ByteBuffer.wrap(sample, 0, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
        val textBytes = if (declaredLength > 0 && declaredLength <= sample.size - 2) {
            sample.copyOfRange(2, 2 + declaredLength)
        } else {
            sample.copyOfRange(2, sample.size)
        }
        return decodeChapterText(textBytes)
    }

    private fun decodeChapterText(bytes: ByteArray): String? {
        val text = when {
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes.copyOfRange(2, bytes.size), StandardCharsets.UTF_16BE)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes.copyOfRange(2, bytes.size), StandardCharsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0.toByte() && bytes[1] != 0.toByte() ->
                String(bytes, StandardCharsets.UTF_16BE)
            else -> String(bytes, StandardCharsets.UTF_8)
        }
        return text.trim { it <= ' ' }.takeIf { it.isNotBlank() }
    }

    private suspend fun readFullAtomPayload(
        atom: Atom,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?
    ): ByteArray? {
        val length = (atom.size - atom.headerSize).takeIf { it in 1..MAX_TABLE_BYTES.toLong() }?.toInt() ?: return null
        return readRange(atom.contentOffset, length)
    }

    private fun List<ChapterEntity>.normalizedChapterDurations(totalDurationMs: Long): List<ChapterEntity> {
        val sorted = distinctBy { it.startPositionMs }.sortedBy { it.startPositionMs }
        return sorted.mapIndexed { index, chapter ->
            val nextStart = sorted.getOrNull(index + 1)?.startPositionMs
            val inferredDuration = when {
                nextStart != null -> nextStart - chapter.startPositionMs
                totalDurationMs > chapter.startPositionMs -> totalDurationMs - chapter.startPositionMs
                else -> chapter.durationMs
            }
            chapter.copy(index = index, durationMs = inferredDuration.coerceAtLeast(0L))
        }
    }

    private suspend fun findChildAtom(
        parent: Atom,
        fileSize: Long,
        type: String,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?,
        extraHeaderBytes: Int = 0
    ): Atom? =
        findAtom(parent.contentOffset + extraHeaderBytes, parent.endOffset, fileSize, type, readRange)

    private suspend fun findChildAtoms(
        parent: Atom,
        fileSize: Long,
        type: String,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?,
        extraHeaderBytes: Int = 0
    ): List<Atom> {
        // QuickTime chapters require scanning all trak elements instead of just matching the first child atom.
        val atoms = mutableListOf<Atom>()
        var pos = parent.contentOffset + extraHeaderBytes
        var guard = 0
        while (pos + ATOM_HEADER_SIZE <= parent.endOffset && guard++ < MAX_ATOM_SCAN_COUNT) {
            val atom = readAtom(pos, parent.endOffset, readRange) ?: break
            if (atom.type == type) atoms.add(atom)
            val next = atom.nextOffsetOrBreak(pos)
            if (next > fileSize) break
            pos = next
        }
        return atoms
    }

    private suspend fun findAtom(
        start: Long,
        end: Long,
        fileSize: Long,
        type: String,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?
    ): Atom? {
        var pos = start
        var guard = 0
        while (pos + ATOM_HEADER_SIZE <= end && guard++ < MAX_ATOM_SCAN_COUNT) {
            val atom = readAtom(pos, end, readRange) ?: break
            if (atom.type == type) return atom
            val next = atom.nextOffsetOrBreak(pos)
            if (next > fileSize) break
            pos = next
        }
        return null
    }

    private suspend fun readAtom(
        offset: Long,
        end: Long,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?
    ): Atom? {
        val header = readRange(offset, EXTENDED_ATOM_HEADER_SIZE) ?: return null
        if (header.size < ATOM_HEADER_SIZE) return null
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val size32 = buffer.int.toLong() and 0xffffffffL
        val typeBytes = ByteArray(4).also { buffer.get(it) }
        val type = String(typeBytes, StandardCharsets.ISO_8859_1)
        val headerSize = if (size32 == 1L) EXTENDED_ATOM_HEADER_SIZE else ATOM_HEADER_SIZE
        val size = when (size32) {
            0L -> end - offset
            1L -> if (header.size >= EXTENDED_ATOM_HEADER_SIZE) buffer.long else return null
            else -> size32
        }
        if (size < headerSize || offset + size > end) return null
        return Atom(offset, size, type, headerSize)
    }

    private fun Atom.nextOffsetOrBreak(currentOffset: Long): Long {
        val next = offset + size
        return if (next > currentOffset) next else Long.MAX_VALUE
    }

    private class RangeReadStats(private val mode: String) {
        var calls: Int = 0
            private set
        var bytes: Long = 0L
            private set

        suspend fun read(block: suspend () -> ByteArray?): ByteArray? {
            calls++
            return block()?.also { bytes += it.size.toLong() }
        }

        override fun toString(): String = "mode=$mode calls=$calls bytes=$bytes"
    }

    private data class RangeWindow(
        val offset: Long,
        val bytes: ByteArray
    ) {
        val endExclusive: Long get() = offset + bytes.size

        fun contains(offset: Long, length: Int): Boolean =
            offset >= this.offset && offset + length <= endExclusive

        fun slice(offset: Long, length: Int): ByteArray {
            val start = (offset - this.offset).toInt()
            return bytes.copyOfRange(start, start + length)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as RangeWindow

            if (offset != other.offset) return false
            if (!bytes.contentEquals(other.bytes)) return false
            if (endExclusive != other.endExclusive) return false

            return true
        }

        override fun hashCode(): Int {
            var result = offset.hashCode()
            result = 31 * result + bytes.contentHashCode()
            result = 31 * result + endExclusive.hashCode()
            return result
        }
    }

    private class CoalescingRangeReader(
        private val fileSize: Long,
        private val stats: RangeReadStats,
        private val readRange: suspend (offset: Long, length: Int) -> ByteArray?
    ) {
        private val windows = object : LinkedHashMap<Long, RangeWindow>(MAX_RANGE_CACHE_WINDOWS, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, RangeWindow>?): Boolean =
                size > MAX_RANGE_CACHE_WINDOWS
        }

        suspend fun read(offset: Long, length: Int): ByteArray? {
            // The coalescing layer only caches small snippet reads; large payloads like covers bypass cache to avoid memory bloat.
            if (offset < 0L) return null
            if (length <= 0) return ByteArray(0)
            if (length > MAX_COALESCED_REQUEST_BYTES) {
                return stats.read { readRange(offset, length) }
            }
            windows.values.firstOrNull { it.contains(offset, length) }?.let { window ->
                return window.slice(offset, length)
            }
            val requestLength = coalescedLength(offset, length)
            val windowBytes = stats.read { readRange(offset, requestLength) } ?: return null
            if (windowBytes.isEmpty()) return windowBytes
            val window = RangeWindow(offset, windowBytes)
            windows[offset] = window
            return if (window.contains(offset, length)) {
                window.slice(offset, length)
            } else {
                windowBytes.copyOfRange(0, minOf(length, windowBytes.size))
            }
        }

        private fun coalescedLength(offset: Long, requestedLength: Int): Int {
            // Header/atom reads are expanded into fixed windows; remote WebDAV ranges are trimmed to valid bounds based on fileSize.
            val desired = maxOf(requestedLength, COALESCED_RANGE_WINDOW_BYTES)
            if (fileSize <= 0L || offset >= fileSize) return requestedLength
            return (fileSize - offset).coerceAtMost(desired.toLong()).toInt().coerceAtLeast(requestedLength)
        }
    }

    private fun logRangeStats(sourceId: String, stats: RangeReadStats, startedAt: Long, success: Boolean, options: ReadOptions) {
        // Preserves log entries for MP4 parse counts and bytes consumed; both local and remote operations use vfs-range statistics.
        Log.d(TAG, "MP4 metadata parse purpose=${options.purpose} success=$success elapsedMs=${SystemClock.elapsedRealtime() - startedAt} $stats source=$sourceId")
    }

    // Time conversion (Refactored duration-to-ms calculation to prevent 64-bit overflow during duration * 1000L in MP4 v1 format)
    // Uses (duration / timescale) * 1000 + ((duration % timescale) * 1000) / timescale to avoid large multiplications,
    // and enforces coerceAtLeast(0L) to guarantee non-negative duration outcomes, protecting player seek logic.
    private fun durationToMs(duration: Long, timescale: Long): Long {
        if (duration <= 0L || timescale <= 0L) return 0L
        return runCatching {
            val base = (duration / timescale) * 1000L
            val remainder = ((duration % timescale) * 1000L) / timescale
            (base + remainder).coerceAtLeast(0L)
        }.getOrDefault(0L)
    }

    private fun Map<String, String>.firstText(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> get(key)?.takeIf { it.isNotBlank() } }.orEmpty()

    private fun String.isMp4FamilyName(): Boolean =
        endsWith(".m4b", ignoreCase = true) ||
            endsWith(".m4a", ignoreCase = true) ||
            endsWith(".mp4", ignoreCase = true)

    private const val ATOM_HEADER_SIZE = 8
    private const val EXTENDED_ATOM_HEADER_SIZE = 16
    private const val DATA_HEADER_SIZE = 8
    private const val FREEFORM_LABEL_HEADER_BYTES = 4
    private const val MAX_ATOM_SCAN_COUNT = 20_000
    private const val MAX_TEXT_DATA_BYTES = 256 * 1024
    // Safety limit (Caps single embedded cover extraction size to 6MB, down from 16MB)
    // Intercepts excessively large or malformed high-resolution covers during tag parsing,
    // avoiding large byte arrays from saturating heap memory and triggering OutOfMemoryError.
    private const val MAX_COVER_BYTES = 6 * 1024 * 1024
    private const val MAX_CHPL_BYTES = 1024 * 1024
    private const val MAX_TABLE_BYTES = 2 * 1024 * 1024
    private const val MAX_TABLE_ENTRIES = 10_000
    private const val MAX_CHAPTER_SAMPLES = 5_000
    private const val MAX_CHAPTER_TEXT_BYTES = 64 * 1024
    private const val MAX_CHAPTER_TRACK_REFERENCES = 32
    private const val COALESCED_RANGE_WINDOW_BYTES = 64 * 1024
    private const val MAX_COALESCED_REQUEST_BYTES = 16 * 1024
    private const val MAX_RANGE_CACHE_WINDOWS = 32
    private const val TAG = "Mp4Meta"
    private val FULL_READ_OPTIONS = ReadOptions(
        includeMetadataFields = true,
        includeCover = true,
        includeChapters = true,
        includeDuration = true,
        purpose = "full"
    )
    private val METADATA_ONLY_OPTIONS = ReadOptions(
        includeMetadataFields = true,
        includeCover = false,
        includeChapters = true,
        includeDuration = true,
        purpose = "metadata"
    )
    private val METADATA_WITH_COVER_OPTIONS = ReadOptions(
        includeMetadataFields = true,
        includeCover = true,
        includeChapters = true,
        includeDuration = true,
        // Standardized metadata purpose after evaluation to establish the formal metadata-cover path for diagnostics comparison.
        purpose = "metadata-cover"
    )
    private val COVER_ONLY_OPTIONS = ReadOptions(
        includeMetadataFields = false,
        includeCover = true,
        includeChapters = false,
        includeDuration = false,
        purpose = "cover"
    )
}
