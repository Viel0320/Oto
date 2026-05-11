package com.viel.aplayer.data

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.OptIn
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.CommentFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import com.viel.aplayer.ui.components.SubtitleLine
import com.viel.aplayer.util.SubtitleParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import androidx.core.content.edit
import androidx.core.net.toUri

/**
 * Repository that wraps Room database and handles cover art caching.
 */
@OptIn(UnstableApi::class)
class LibraryRepository private constructor(context: Context) {
    @SuppressLint("StaticFieldLeak")
    private val context = context.applicationContext
    private val database = AppDatabase.getInstance(this.context)
    private val dao = database.audiobookDao()
    private val chapterDao = database.chapterDao()
    private val bookmarkDao = database.bookmarkDao()
    private val coversDir = File(this.context.filesDir, "covers").also { it.mkdirs() }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = this.context.getSharedPreferences("library_prefs", Context.MODE_PRIVATE)

    /** Set the root library directory. */
    fun setLibraryRoot(uri: Uri) {
        prefs.edit { putString(KEY_LIBRARY_URI, uri.toString()) }
    }

    /** Get the root library directory. */
    fun getLibraryRoot(): Uri? {
        return prefs.getString(KEY_LIBRARY_URI, null)?.toUri()
    }

    /** Check if a file exists at the given URI. */
    fun checkFileExists(uriString: String): Boolean {
        return try {
            val uri = uriString.toUri()
            if (uri.scheme == "content") {
                val doc = DocumentFile.fromSingleUri(context, uri)
                doc?.exists() == true
            } else {
                File(uri.path ?: "").exists()
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Sync the entire library: scan folder and add new files.
     */
    fun syncLibrary() {
        val rootUri = getLibraryRoot() ?: return
        scope.launch {
            val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return@launch
            val existingUris = dao.getAllUris().toSet()
            scanDirectory(rootDoc, existingUris)
            
            // TODO: 实现手动清理逻辑。
            // 扫描并识别出已不存在的文件（existingUris - scannedUris），但暂时不自动删除，
            // 留待将来在设置或库管理界面由用户手动确认后触发清理。
        }
    }

    private fun scanDirectory(directory: DocumentFile, existingUris: Set<String>) {
        directory.listFiles().forEach { file ->
            if (file.isDirectory) {
                scanDirectory(file, existingUris)
            } else if (isAudioFile(file.name ?: "")) {
                val uriStr = file.uri.toString()
                if (!existingUris.contains(uriStr)) {
                    addAudiobook(file.uri, directory, file.name)
                }
            }
        }
    }

    private fun isAudioFile(fileName: String): Boolean {
        val extensions = listOf(".mp3", ".m4b", ".m4a", ".aac", ".flac", ".wav", ".ogg")
        return extensions.any { fileName.endsWith(it, ignoreCase = true) }
    }
    
    /** Observable list of all audiobooks, sorted by last played. */
    val audiobooks: Flow<List<AudiobookEntity>> = dao.getAllAudiobooks()

    /**
     * Import an audiobook: extract metadata, insert into DB, and cache cover.
     */
    fun addAudiobook(uri: Uri, parentDir: DocumentFile? = null, fileName: String? = null) {
        scope.launch {
            Log.d("LibraryRepository", "Adding audiobook: $uri (Name: $fileName)")
            val retriever = MediaMetadataRetriever()
            var title: String
            var author: String
            var narrator: String
            var description = ""
            var duration: Long
            var year = ""
            var fileSize = 0L
            
            try {
                // Extract file size
                if (uri.scheme == "content") {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            fileSize = cursor.getLong(sizeIndex)
                        }
                    }
                } else if (uri.scheme == "file") {
                    fileSize = File(uri.path ?: "").length()
                }

                retriever.setDataSource(context, uri)
                val rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                
                // Extract year/date
                val rawYear = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR) 
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                if (!rawYear.isNullOrBlank()) {
                    year = Regex("\\d{4}").find(rawYear)?.value ?: rawYear
                }

                // Extract duration
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                duration = durationStr?.toLongOrNull() ?: 0L

                // Title/Author extraction
                title = if (!rawTitle.isNullOrBlank() && !rawTitle.contains("/")) {
                    rawTitle
                } else {
                    uri.lastPathSegment?.substringAfterLast("/")?.substringBeforeLast(".") ?: "Unknown Title"
                }

                author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown Author"
                narrator = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER) ?: "Unknown Narrator"

                // Try to extract description using Media3's MetadataRetriever for better ID3 support (COMM frame)
                try {
                    val mediaItem = MediaItem.Builder()
                        .setUri(uri)
                        .setMimeType(if (uri.toString().endsWith(".m4b", ignoreCase = true)) "audio/mp4" else null)
                        .build()
                    val trackGroups = withContext(Dispatchers.IO) {
                        MetadataRetriever.retrieveMetadata(context, mediaItem).get()
                    }
                    for (i in 0 until trackGroups.length) {
                        val metadata = trackGroups[i].getFormat(0).metadata ?: continue
                        for (j in 0 until metadata.length()) {
                            val entry = metadata[j]
                            if (entry is CommentFrame) {
                                description = entry.text
                                break
                            }
                        }
                        if (description.isNotEmpty()) break
                    }
                } catch (_: Exception) {
                    // Fallback or ignore if Media3 extraction fails
                }

                val entity = AudiobookEntity(
                    uri = uri.toString(),
                    title = title,
                    author = author,
                    narrator = narrator,
                    description = description,
                    duration = duration,
                    year = year,
                    fileSize = fileSize,
                    subtitlePath = findSubtitleFile(uri, parentDir, fileName),
                    addedAt = System.currentTimeMillis()
                )
                Log.d("LibraryRepository", "Inserting entity: ${entity.title}, Subtitle: ${entity.subtitlePath}")
                dao.insert(entity)

                // Extract chapters
                extractAndSaveChapters(uri)

            } catch (_: Exception) {
                // Log or handle error
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
            
            // Extract and save cover art in background
            val coverPath = extractAndSaveCover(uri)
            if (coverPath != null) {
                dao.updateCoverPath(uri.toString(), coverPath)
            }
        }
    }
    
    /** Update playback position in milliseconds. */
    fun updateProgress(uri: String, position: Long) {
        scope.launch {
            Log.d("LibraryRepository", "Updating progress for $uri: $position")
            dao.updateProgress(uri, position, System.currentTimeMillis())
        }
    }

    /**
     * Extract chapters from media and save to database.
     */
    private suspend fun extractAndSaveChapters(uri: Uri) {
        // 1. First try the high-level Media3/ExoPlayer extraction
        val chapters = mutableListOf<ChapterEntity>()
        try {
            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .setMimeType(if (uri.toString().endsWith(".m4b", ignoreCase = true)) "audio/mp4" else null)
                .build()
            
            val extractorsFactory = DefaultExtractorsFactory()
                .setMp4ExtractorFlags(
                    androidx.media3.extractor.mp4.Mp4Extractor.FLAG_READ_MOTION_PHOTO_METADATA or
                    androidx.media3.extractor.mp4.Mp4Extractor.FLAG_READ_SEF_DATA
                )
            
            val mediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)
            val trackGroups = withContext(Dispatchers.IO) {
                MetadataRetriever.retrieveMetadata(mediaSourceFactory, mediaItem).get()
            }
            
            for (i in 0 until trackGroups.length) {
                val group = trackGroups[i]
                for (j in 0 until group.length) {
                    val format = group.getFormat(j)
                    val metadata = format.metadata ?: continue
                    
                    for (k in 0 until metadata.length()) {
                        val entry = metadata.get(k)
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
                                    bookUri = uri.toString(),
                                    title = title ?: "Chapter ${chapters.size + 1}",
                                    startPosition = entry.startTimeMs.toLong(),
                                    endPosition = entry.endTimeMs.toLong()
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. If high-level failed or got nothing, use the "Binary Tracker" (Low-Level Parser)
        if (chapters.isEmpty()) {
            try {
                chapters.addAll(extractChaptersLowLevel(uri))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // Distinct by start position and sort
        val finalChapters = chapters.asSequence().distinctBy { it.startPosition }.sortedBy { it.startPosition }.toList()
        
        if (finalChapters.isNotEmpty()) {
            chapterDao.deleteChaptersForBook(uri.toString())
            chapterDao.insertChapters(finalChapters)
        }
    }

    /**
     * Binary Tracker: Bypasses Android's MediaExtractor to parse MP4 atoms directly.
     * Extracts Nero 'chpl' and QuickTime chapters.
     */
    private fun extractChaptersLowLevel(uri: Uri): List<ChapterEntity> {
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
                        chapters.addAll(parseChpl(channel, chpl, uri))
                    }

                    // 3. Try QuickTime Chapter Tracks (tref -> chap)
                    if (chapters.isEmpty()) {
                        chapters.addAll(parseQuickTimeChapters(channel, moov, uri))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return chapters
    }

    private fun parseQuickTimeChapters(channel: FileChannel, moov: Atom, uri: Uri): List<ChapterEntity> {
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

            // Typically, the first audio track's chapter track is what we want
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
                
                // stts: Time-to-sample
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

                // stco: Chunk offset
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

                // stsz: Sample sizes
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

                // Read sample data
                var currentTime = 0L
                for (i in 0 until offsets.size.coerceAtMost(sizes.size)) {
                    val offset = offsets[i]
                    val size = sizes[i]
                    if (size > 2) {
                        val sampleBuf = ByteBuffer.allocate(size)
                        channel.position(offset)
                        channel.read(sampleBuf)
                        sampleBuf.flip()
                        // tx3g format: first 2 bytes are length
                        val textLen = sampleBuf.short.toInt() and 0xffff
                        if (textLen > 0 && textLen <= sampleBuf.remaining()) {
                            val bytes = ByteArray(textLen)
                            sampleBuf.get(bytes)
                            val title = String(bytes, Charsets.UTF_8)
                            list.add(ChapterEntity(
                                bookUri = uri.toString(),
                                title = title,
                                startPosition = (currentTime * 1000) / timeScale,
                                endPosition = 0
                            ))
                        }
                    }
                    if (i < sampleDurations.size) currentTime += sampleDurations[i]
                }
                
                // Set end positions
                for (i in 0 until list.size - 1) {
                    list[i] = list[i].copy(endPosition = list[i+1].startPosition)
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

    private fun parseChpl(channel: FileChannel, chpl: Atom, uri: Uri): List<ChapterEntity> {
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
                        bookUri = uri.toString(),
                        title = title,
                        startPosition = time100ns / 10000, // convert to ms
                        endPosition = 0
                    )
                )
            }
            
            // Set end positions
            for (i in 0 until list.size - 1) {
                val current = list[i]
                val next = list[i+1]
                list[i] = current.copy(endPosition = next.startPosition)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    /**
     * Get the most recently played audiobook.
     */
    suspend fun getMostRecentAudiobook(): AudiobookEntity? = dao.getMostRecent()

    /**
     * Get chapters for an audiobook.
     */
    fun getChapters(uri: String): Flow<List<ChapterEntity>> = chapterDao.getChaptersForBook(uri)

    /**
     * Get bookmarks for an audiobook.
     */
    fun getBookmarks(uri: String): Flow<List<BookmarkEntity>> = bookmarkDao.getBookmarksForBook(uri)

    /**
     * Add a bookmark.
     */
    suspend fun addBookmark(uri: String, position: Long, title: String) {
        bookmarkDao.insert(BookmarkEntity(bookUri = uri, position = position, title = title))
    }

    /**
     * Delete a bookmark.
     */
    suspend fun deleteBookmark(bookmark: BookmarkEntity) {
        bookmarkDao.delete(bookmark)
    }

    /**
     * Load and parse subtitles for an audiobook.
     */
    suspend fun loadSubtitles(bookUri: String): List<SubtitleLine> {
        val entity = dao.getByUri(bookUri) ?: run {
            Log.d("LibraryRepository", "loadSubtitles: Entity not found for $bookUri")
            return emptyList()
        }
        val subtitlePath = entity.subtitlePath ?: run {
            Log.d("LibraryRepository", "loadSubtitles: subtitlePath is null for $bookUri")
            return emptyList()
        }
        
        Log.d("LibraryRepository", "loadSubtitles: Path is $subtitlePath")
        return withContext(Dispatchers.IO) {
            try {
                val uri = subtitlePath.toUri()
                val extension = subtitlePath.substringAfterLast(".").lowercase()
                
                if (uri.scheme == "content") {
                    context.contentResolver.openInputStream(uri)?.use { 
                        SubtitleParser.parse(it, extension)
                    } ?: emptyList()
                } else {
                    val file = File(subtitlePath)
                    if (file.exists()) {
                        file.inputStream().use {
                            SubtitleParser.parse(it, extension)
                        }
                    } else emptyList()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    /**
     * Save chapters to database.
     */
    fun saveChapters(uri: String, chapters: List<ChapterEntity>) {
        scope.launch {
            chapterDao.deleteChaptersForBook(uri)
            chapterDao.insertChapters(chapters)
        }
    }

    /**
     * Update title/author/narrator/description from ExoPlayer's parsed metadata.
     */
    fun updateMetadata(uri: String, title: String?, author: String?, narrator: String?, description: String?, duration: Long) {
        scope.launch {
            val existing = dao.getByUri(uri) ?: return@launch
            val newTitle = if (!title.isNullOrBlank()) title else existing.title
            val newAuthor = if (!author.isNullOrBlank()) author else existing.author
            val newNarrator = if (!narrator.isNullOrBlank()) narrator else existing.narrator
            val newDescription = if (!description.isNullOrBlank()) description else existing.description
            val newDuration = if (duration > 0) duration else existing.duration
            
            if (newTitle != existing.title || newAuthor != existing.author || 
                newNarrator != existing.narrator || newDescription != existing.description ||
                newDuration != existing.duration) {
                dao.updateMetadata(uri, newTitle, newAuthor, newNarrator, newDescription, newDuration)
            }
        }
    }
    
    /**
     * Get the cover path for a specific audiobook URI.
     */
    suspend fun getCoverPath(bookUri: String): String? {
        return dao.getByUri(bookUri)?.coverPath
    }
    
    /**
     * Extract embedded cover art from the audio file and save it to internal storage.
     */
    private fun extractAndSaveCover(uri: Uri): String? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val artBytes = retriever.embeddedPicture
            retriever.release()
            
            if (artBytes != null) {
                val coverFile = File(coversDir, "${uri.toString().hashCode()}.jpg")
                FileOutputStream(coverFile).use { it.write(artBytes) }
                coverFile.absolutePath
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Search for a subtitle file (srt, ass, ssa) in the same directory as the media file.
     */
    private fun findSubtitleFile(mediaUri: Uri, providedParent: DocumentFile? = null, providedName: String? = null): String? {
        return try {
            when (mediaUri.scheme) {
                "content" -> {
                    // If we are in syncLibrary, providedParent is already the correct folder
                    val parentDir = providedParent ?: run {
                        val mediaFile = DocumentFile.fromSingleUri(context, mediaUri)
                        mediaFile?.parentFile
                    } ?: return null

                    val mediaName = (providedName ?: DocumentFile.fromSingleUri(context, mediaUri)?.name)
                        ?.substringBeforeLast(".") ?: return null

                    Log.d("LibraryRepository", "Searching subtitles in ${parentDir.uri} for $mediaName")
                    val subtitleExtensions = listOf("srt", "ass", "ssa")

                    // Optimized search: only list files if needed
                    val found = parentDir.listFiles().find { file ->
                        val fileName = file.name ?: ""
                        val nameWithoutExt = fileName.substringBeforeLast(".")
                        val ext = fileName.substringAfterLast(".").lowercase()
                        nameWithoutExt.equals(mediaName, ignoreCase = true) && subtitleExtensions.contains(ext)
                    }

                    Log.d("LibraryRepository", "Found subtitle: ${found?.uri}")
                    found?.uri?.toString()
                }
                "file" -> {
                    val file = File(mediaUri.path ?: "")
                    val parentDir = file.parentFile ?: return null
                    val baseName = file.nameWithoutExtension
                    val subtitleExtensions = listOf("srt", "ass", "ssa")

                    parentDir.listFiles { _, name ->
                        val ext = name.substringAfterLast(".").lowercase()
                        name.substringBeforeLast(".") == baseName && subtitleExtensions.contains(ext)
                    }?.firstOrNull()?.absolutePath
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e("LibraryRepository", "Error finding subtitle", e)
            null
        }
    }
    
    companion object {
        private const val KEY_LIBRARY_URI = "library_root_uri"

        @Volatile
        @SuppressLint("StaticFieldLeak")
        private var INSTANCE: LibraryRepository? = null
        
        fun getInstance(context: Context): LibraryRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LibraryRepository(context).also { INSTANCE = it }
            }
        }
    }
}
