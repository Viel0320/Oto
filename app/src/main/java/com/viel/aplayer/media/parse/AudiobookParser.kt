package com.viel.aplayer.media.parse

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.UUID
import com.viel.aplayer.data.entity.ChapterEntity

@OptIn(UnstableApi::class)
object AudiobookParser {

    /**
     * Extracts chapters from a list of Media3 Metadata entries (usually from Player or MetadataRetriever).
     */
    fun extractChaptersFromMetadata(entries: List<Metadata.Entry>, bookId: String): List<ChapterEntity> {
        val chapters = mutableListOf<ChapterEntity>()
        for (entry in entries) {
            if (entry is ChapterFrame) {
                var title: String? = null
                try {
                    val field = entry.javaClass.getDeclaredField("subFrames")
                    field.isAccessible = true
                    val subFrames = field.get(entry) as? Array<*>
                    subFrames?.forEach { subFrame ->
                        if (subFrame is TextInformationFrame && (subFrame.id == "TIT2" || subFrame.id == "TIT1")) {
                            title = subFrame.values.firstOrNull()
                        }
                    }
                } catch (_: Exception) {}

                if (title.isNullOrBlank() && !entry.chapterId.matches(Regex("ch\\d+"))) {
                    title = entry.chapterId
                }

                chapters.add(
                    ChapterEntity(
                        id = UUID.randomUUID().toString(),
                        bookId = bookId,
                        bookFileId = "", // Filled by importer
                        index = chapters.size,
                        title = title ?: "Chapter ${chapters.size + 1}",
                        startPositionMs = entry.startTimeMs.toLong(),
                        durationMs = (entry.endTimeMs - entry.startTimeMs).toLong(),
                        fileOffsetMs = entry.startTimeMs.toLong(),
                        source = "EMBEDDED"
                    )
                )
            } else if (entry.javaClass.simpleName.contains("Chapter", ignoreCase = true)) {
                try {
                    val clazz = entry.javaClass
                    val title = try { clazz.getDeclaredField("title").apply { isAccessible = true }.get(entry) as? String } catch(_: Exception) { null }
                        ?: try { clazz.getDeclaredField("text").apply { isAccessible = true }.get(entry) as? String } catch(_: Exception) { null }
                    val startTimeMs = try { clazz.getDeclaredField("startTimeMs").apply { isAccessible = true }.get(entry) as? Int } catch(_: Exception) { null }
                    val endTimeMs = try { clazz.getDeclaredField("endTimeMs").apply { isAccessible = true }.get(entry) as? Int } catch(_: Exception) { null }

                    if (startTimeMs != null && endTimeMs != null) {
                        chapters.add(
                            ChapterEntity(
                                id = UUID.randomUUID().toString(),
                                bookId = bookId,
                                bookFileId = "",
                                index = chapters.size,
                                title = title ?: "Chapter ${chapters.size + 1}",
                                startPositionMs = startTimeMs.toLong(),
                                durationMs = (endTimeMs - startTimeMs).toLong(),
                                fileOffsetMs = startTimeMs.toLong(),
                                source = "EMBEDDED"
                            )
                        )
                    }
                } catch (_: Exception) {}
            }
        }
        return chapters.asSequence().distinctBy { it.startPositionMs }.sortedBy { it.startPositionMs }.toList()
    }

    /**
     * Binary Tracker: Bypasses Android's MediaExtractor to parse MP4 atoms directly.
     * Extracts Nero 'chpl' and QuickTime chapters.
     */
    fun extractChaptersLowLevel(context: Context, uri: Uri): List<ChapterEntity> {
        val chapters = mutableListOf<ChapterEntity>()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                    val fileSize = channel.size()
                    // 1. Locate moov
                    val moov = findAtom(channel, 0, fileSize, "moov") ?: return emptyList()

                    // 2. Try parsing Nero chpl (Common for FFmpeg -map_chapters)
                    val udta = findAtom(channel, moov.offset + 8, moov.size - 8, "udta") ?: return@use
                    val chpl = findAtom(channel, udta.offset + 8, udta.size - 8, "chpl")
                    if (chpl != null) {
                        chapters.addAll(parseChpl(channel, chpl, uri.toString()))
                    }

                    // 3. Try QuickTime Chapter Tracks (tref -> chap)
                    if (chapters.isEmpty()) {
                        chapters.addAll(parseQuickTimeChapters(channel, moov, uri.toString()))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return chapters.asSequence().distinctBy { it.startPositionMs }.sortedBy { it.startPositionMs }.toList()
    }

    private fun parseQuickTimeChapters(channel: FileChannel, moov: Atom, bookId: String): List<ChapterEntity> {
        val list = mutableListOf<ChapterEntity>()
        try {
            // Find all traks
            var pos = moov.offset + 8
            val limit = moov.offset + moov.size
            val trakIdsWithChapters = mutableMapOf<Int, Int>() // TrackID -> ChapterTrackID
            val tracks = mutableMapOf<Int, Atom>() // TrackID -> TrakAtom

            while (pos + 8 <= limit) {
                val trak = findAtom(channel, pos, limit - pos, "trak") ?: break
                pos = trak.offset + trak.size
                
                val tkhd = findAtom(channel, trak.offset + 8, trak.size - 8, "tkhd") ?: continue
                
                val buf = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
                channel.position(tkhd.offset + 8)
                channel.read(buf)
                buf.flip()
                val version = buf.get().toInt()
                buf.position(if (version == 1) 20 else 12)
                val trackId = buf.int
                tracks[trackId] = trak

                val tref = findAtom(channel, trak.offset + 8, trak.size - 8, "tref")
                if (tref != null) {
                    val chap = findAtom(channel, tref.offset + 8, tref.size - 8, "chap")
                    if (chap != null) {
                        channel.position(chap.offset + 8)
                        val cb = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                        channel.read(cb)
                        cb.flip()
                        val chapterTrackId = cb.int
                        trakIdsWithChapters[trackId] = chapterTrackId
                    }
                }
            }

            val chapterTrackId = trakIdsWithChapters.values.firstOrNull()
            val chapterTrak = tracks[chapterTrackId]
            
            if (chapterTrak != null) {
                val mdia = findAtom(channel, chapterTrak.offset + 8, chapterTrak.size - 8, "mdia") ?: return list
                val mdhd = findAtom(channel, mdia.offset + 8, mdia.size - 8, "mdhd") ?: return list
                
                val mb = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
                channel.position(mdhd.offset + 8)
                channel.read(mb)
                mb.flip()
                val mVersion = mb.get().toInt()
                mb.position(if (mVersion == 1) 20 else 12)
                val timeScale = mb.int.toLong()

                val minf = findAtom(channel, mdia.offset + 8, mdia.size - 8, "minf") ?: return list
                val stbl = findAtom(channel, minf.offset + 8, minf.size - 8, "stbl") ?: return list
                
                val stts = findAtom(channel, stbl.offset + 8, stbl.size - 8, "stts") ?: return list
                val sttsBuf = ByteBuffer.allocate(stts.size.toInt()).order(ByteOrder.BIG_ENDIAN)
                channel.position(stts.offset + 8)
                channel.read(sttsBuf)
                sttsBuf.flip()
                sttsBuf.get(); sttsBuf.position(sttsBuf.position() + 3) // version/flags
                val entryCount = sttsBuf.int
                val sampleDurations = mutableListOf<Long>()
                repeat(entryCount) {
                    val count = sttsBuf.int
                    val delta = sttsBuf.int
                    repeat(count) { sampleDurations.add(delta.toLong()) }
                }

                val stco = findAtom(channel, stbl.offset + 8, stbl.size - 8, "stco")
                val co64 = if (stco == null) findAtom(channel, stbl.offset + 8, stbl.size - 8, "co64") else null
                val offsets = mutableListOf<Long>()
                if (stco != null) {
                    val b = ByteBuffer.allocate(stco.size.toInt()).order(ByteOrder.BIG_ENDIAN)
                    channel.position(stco.offset + 8)
                    channel.read(b)
                    b.flip()
                    b.get(); b.position(b.position() + 3)
                    val count = b.int
                    repeat(count) { offsets.add(b.int.toLong() and 0xffffffffL) }
                } else if (co64 != null) {
                    val b = ByteBuffer.allocate(co64.size.toInt()).order(ByteOrder.BIG_ENDIAN)
                    channel.position(co64.offset + 8)
                    channel.read(b)
                    b.flip()
                    b.get(); b.position(b.position() + 3)
                    val count = b.int
                    repeat(count) { offsets.add(b.long) }
                }

                val stsz = findAtom(channel, stbl.offset + 8, stbl.size - 8, "stsz") ?: return list
                val szBuf = ByteBuffer.allocate(stsz.size.toInt()).order(ByteOrder.BIG_ENDIAN)
                channel.position(stsz.offset + 8)
                channel.read(szBuf)
                szBuf.flip()
                szBuf.get(); szBuf.position(szBuf.position() + 3)
                val defaultSize = szBuf.int
                val sampleCount = szBuf.int
                val sizes = mutableListOf<Int>()
                repeat(sampleCount) {
                    sizes.add(if (defaultSize == 0) szBuf.int else defaultSize)
                }

                var currentTime = 0L
                for (i in 0 until offsets.size.coerceAtMost(sizes.size)) {
                    val offset = offsets[i]
                    val size = sizes[i]
                    if (size > 2) {
                        val sampleBuf = ByteBuffer.allocate(size)
                        channel.position(offset)
                        channel.read(sampleBuf)
                        sampleBuf.flip()
                        val textLen = sampleBuf.short.toInt() and 0xffff
                        if (textLen > 0 && textLen <= sampleBuf.remaining()) {
                            val bytes = ByteArray(textLen)
                            sampleBuf.get(bytes)
                            val title = String(bytes, Charsets.UTF_8)
                            list.add(ChapterEntity(
                                id = UUID.randomUUID().toString(),
                                bookId = bookId,
                                bookFileId = "",
                                index = list.size,
                                title = title,
                                startPositionMs = (currentTime * 1000) / timeScale,
                                durationMs = 0,
                                fileOffsetMs = (currentTime * 1000) / timeScale,
                                source = "EMBEDDED"
                            ))
                        }
                    }
                    if (i < sampleDurations.size) currentTime += sampleDurations[i]
                }
                
                val trackDurationMs = (currentTime * 1000) / timeScale
                for (i in list.indices) {
                    val nextStart = if (i < list.size - 1) list[i+1].startPositionMs else trackDurationMs
                    list[i] = list[i].copy(durationMs = (nextStart - list[i].startPositionMs).coerceAtLeast(0L))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private data class Atom(val offset: Long, val size: Long, val type: String)

    private fun findAtom(channel: FileChannel, start: Long, limit: Long, type: String): Atom? {
        var pos = start
        val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        while (pos + 8 <= start + limit) {
            channel.position(pos)
            buf.clear()
            if (channel.read(buf) < 8) break
            buf.flip()
            var size = buf.int.toLong() and 0xffffffffL
            val t = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
            
            if (size == 1L) {
                val lb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                channel.read(lb)
                lb.flip()
                size = lb.long
            }
            
            if (t == type) {
                return Atom(pos, size, t)
            }
            if (size <= 0) break
            pos += size
        }
        return null
    }

    private fun parseChpl(channel: FileChannel, chpl: Atom, bookId: String): List<ChapterEntity> {
        val list = mutableListOf<ChapterEntity>()
        try {
            val buf = ByteBuffer.allocate(chpl.size.toInt().coerceAtMost(1024 * 1024)).order(ByteOrder.BIG_ENDIAN)
            channel.position(chpl.offset + 8)
            channel.read(buf)
            buf.flip()
            if (buf.remaining() < 9) return list
            
            buf.get() // version
            buf.position(buf.position() + 3) // flags
            buf.position(buf.position() + 4) // reserved
            val count = buf.get().toInt() and 0xff
            
            repeat(count) {
                if (buf.remaining() < 9) return@repeat
                val time100ns = buf.long // start time in 100ns units
                val len = buf.get().toInt() and 0xff
                if (buf.remaining() < len) return@repeat
                val titleBytes = ByteArray(len).also { buf.get(it) }
                val title = titleBytes.toString(Charsets.UTF_8)
                
                list.add(
                    ChapterEntity(
                        id = UUID.randomUUID().toString(),
                        bookId = bookId,
                        bookFileId = "",
                        index = list.size,
                        title = title,
                        startPositionMs = time100ns / 10000, // convert to ms
                        durationMs = 0,
                        fileOffsetMs = time100ns / 10000,
                        source = "EMBEDDED"
                    )
                )
            }
            
            // 注意：chpl 原子本身不携带文件总时长，因此最后一个章节的时长
            // 将在 BookImporter 中结合文件实际时长进行二次修正。
            for (i in 0 until list.size - 1) {
                val current = list[i]
                val next = list[i+1]
                list[i] = current.copy(durationMs = next.startPositionMs - current.startPositionMs)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }
}