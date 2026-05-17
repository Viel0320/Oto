package com.viel.aplayer.library

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.viel.aplayer.data.AppDatabase
import com.viel.aplayer.data.ScanSessionEntity
import com.viel.aplayer.library.manifest.CueManifestParser
import com.viel.aplayer.library.manifest.M3u8ManifestParser
import com.viel.aplayer.library.manifest.ManifestResolver
import com.viel.aplayer.library.manifest.HeuristicM3u8Suggester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.UUID

/**
 * 核心扫描引擎。
 * 负责遍历授权目录并生成文件系统快照，支持 Manifest 占用机制。
 */
class LibraryScanner(private val context: Context) {
    private val database = AppDatabase.getInstance(context)
    private val rootDao = database.libraryRootDao()
    private val sessionDao = database.scanSessionDao()

    /**
     * 执行全库扫描。
     * @param trigger 触发方式：COLD_START 或 USER
     */
    suspend fun scanAllRoots(trigger: String = "USER"): LibrarySnapshot = withContext(Dispatchers.IO) {
        val scanId = UUID.randomUUID().toString()
        
        // 1. 记录 Session 开始
        sessionDao.insertSession(ScanSessionEntity(
            id = scanId,
            trigger = trigger,
            status = "RUNNING",
            startedAt = System.currentTimeMillis()
        ))

        val roots = rootDao.getAllRoots().first()
        val allClaims = mutableListOf<ClaimSource>()

        Log.d("LibraryScanner", "Starting scan session $scanId (Trigger: $trigger)")

        for (root in roots) {
            if (root.status != "ACTIVE") continue
            try {
                val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(root.treeUri))
                if (rootDoc != null && rootDoc.exists()) {
                    allClaims.addAll(scanDirectory(rootDoc, root.id))
                }
            } catch (e: Exception) {
                Log.e("LibraryScanner", "Error scanning root ${root.id}", e)
            }
        }

        // 2. 处理 Claim 冲突与过滤
        val filteredClaims = resolveClaims(allClaims)
        
        Log.d("LibraryScanner", "Scan complete. Found ${filteredClaims.size} books.")
        
        LibrarySnapshot(scanId, roots, filteredClaims)
    }

    private suspend fun scanDirectory(directory: DocumentFile, rootId: String): List<ClaimSource> {
        val claims = mutableListOf<ClaimSource>()
        val files = directory.listFiles()
        
        val audioFiles = mutableListOf<DocumentFile>()
        val manifestFiles = mutableListOf<DocumentFile>()

        for (file in files) {
            yield()
            if (file.isDirectory) {
                claims.addAll(scanDirectory(file, rootId))
            } else {
                val fileName = file.name ?: continue
                if (isAudioFile(fileName)) audioFiles.add(file)
                else if (isManifestFile(fileName)) manifestFiles.add(file)
            }
        }

        val directoryCoverUri = findImageInDirectory(directory)?.uri?.toString()

        // 1. 解析 CUE
        for (manifest in manifestFiles.filter { it.name?.lowercase()?.endsWith(".cue") == true }) {
            val result = CueManifestParser.parse(context, manifest)
            if (result != null) {
                val resolvedUris = mutableListOf<String>()
                val relativeToFullUri = mutableMapOf<String, String>()

                result.referencedFiles.forEach { relPath ->
                    val resolved = ManifestResolver.resolveRelativePath(directory, relPath)
                    if (resolved != null) {
                        val fullUri = resolved.uri.toString()
                        resolvedUris.add(fullUri)
                        relativeToFullUri[relPath] = fullUri
                    }
                }

                if (resolvedUris.isNotEmpty()) {
                    // 解析章节，并将相对路径替换为完整 URI
                    val resolvedChapters = result.chapters.mapNotNull { ch ->
                        val fullUri = relativeToFullUri[ch.fileUri] ?: return@mapNotNull null
                        ch.copy(fileUri = fullUri)
                    }

                    claims.add(ClaimSource(
                        type = ClaimSourceType.CUE,
                        sourceUri = manifest.uri.toString(),
                        rootId = rootId,
                        priority = 1,
                        referencedFileUris = resolvedUris,
                        chapters = resolvedChapters,
                        metadata = result.metadata,
                        sourceFileSize = manifest.length(),
                        sourceLastModified = manifest.lastModified(),
                        coverUri = directoryCoverUri,
                        subtitleUri = findSubtitleForFile(directory, manifest),
                        displayName = result.metadata.title?.trim() ?: manifest.name ?: "",
                        parentUri = directory.uri.toString()
                    ))
                }
            }
        }

        // 2. 解析 M3U8
        for (manifest in manifestFiles.filter { it.name?.lowercase()?.run { endsWith(".m3u8") || endsWith(".m3u") } == true }) {
            val items = M3u8ManifestParser.parse(context, manifest)
            if (items.isNotEmpty()) {
                val resolvedUris = mutableListOf<String>()
                val fileTitles = mutableMapOf<String, String>()
                val fileDurations = mutableMapOf<String, Long>()

                items.forEach { item ->
                    val resolved = ManifestResolver.resolveRelativePath(directory, item.uri)
                    if (resolved != null) {
                        val uriStr = resolved.uri.toString()
                        resolvedUris.add(uriStr)
                        if (item.title != null) fileTitles[uriStr] = item.title
                        if (item.durationMs != null) fileDurations[uriStr] = item.durationMs
                    }
                }

                if (resolvedUris.isNotEmpty()) {
                    claims.add(ClaimSource(
                        type = ClaimSourceType.M3U8,
                        sourceUri = manifest.uri.toString(),
                        rootId = rootId,
                        priority = 2,
                        referencedFileUris = resolvedUris,
                        fileTitles = fileTitles,
                        fileDurations = fileDurations,
                        sourceFileSize = manifest.length(),
                        sourceLastModified = manifest.lastModified(),
                        coverUri = directoryCoverUri,
                        subtitleUri = findSubtitleForFile(directory, manifest),
                        displayName = manifest.name ?: ""
                    ))
                }
            }
        }

        // 3. 收集音频
        val audioClaims = audioFiles.map { audio ->
            val type = if (audio.name?.lowercase()?.endsWith(".m4b") == true) 
                ClaimSourceType.M4B_EMBEDDED else ClaimSourceType.SINGLE_AUDIO
            
            ClaimSource(
                type = type,
                sourceUri = audio.uri.toString(),
                rootId = rootId,
                priority = if (type == ClaimSourceType.M4B_EMBEDDED) 3 else 4,
                referencedFileUris = listOf(audio.uri.toString()),
                sourceFileSize = audio.length(),
                sourceLastModified = audio.lastModified(),
                subtitleUri = findSubtitleForFile(directory, audio),
                displayName = audio.name ?: ""
            )
        }

        val heuristicSuggestions = HeuristicM3u8Suggester.suggest(rootId, directory, audioFiles)
        claims.addAll(audioClaims)
        claims.addAll(heuristicSuggestions)

        return claims
    }

    private fun resolveClaims(allClaims: List<ClaimSource>): List<ClaimSource> {
        val claimedFileUris = mutableSetOf<String>()
        val result = mutableListOf<ClaimSource>()
        val sortedClaims = allClaims.sortedBy { it.priority }

        for (claim in sortedClaims) {
            val alreadyClaimed = claim.referencedFileUris.any { claimedFileUris.contains(it) }
            if (!alreadyClaimed) {
                result.add(claim)
                claimedFileUris.addAll(claim.referencedFileUris)
            }
        }
        return result
    }

    private fun isAudioFile(fileName: String): Boolean {
        val extensions = listOf(".mp3", ".m4b", ".m4a", ".aac", ".flac", ".wav", ".ogg")
        return extensions.any { fileName.endsWith(it, ignoreCase = true) }
    }

    private fun isManifestFile(fileName: String): Boolean {
        val extensions = listOf(".cue", ".m3u8", ".m3u")
        return extensions.any { fileName.endsWith(it, ignoreCase = true) }
    }

    private fun findSubtitleForFile(directory: DocumentFile, mediaFile: DocumentFile): String? {
        val baseName = mediaFile.name?.substringBeforeLast(".") ?: return null
        val subtitleExtensions = listOf("srt", "ass", "ssa", "vtt", "lrc")
        
        return directory.listFiles().find { file ->
            val fileName = file.name ?: ""
            val nameWithoutExt = fileName.substringBeforeLast(".")
            val ext = fileName.substringAfterLast(".").lowercase()
            subtitleExtensions.contains(ext) && nameWithoutExt.equals(baseName, ignoreCase = true)
        }?.uri?.toString()
    }

    private fun findImageInDirectory(directory: DocumentFile): DocumentFile? {
        val imageExtensions = listOf("jpg", "jpeg", "png", "webp")
        val priorityNames = listOf("cover", "folder", "artwork", "front")
        val files = directory.listFiles()
        files.find { file ->
            val name = file.name?.lowercase()?.substringBeforeLast(".") ?: ""
            val ext = file.name?.lowercase()?.substringAfterLast(".") ?: ""
            priorityNames.contains(name) && imageExtensions.contains(ext)
        }?.let { return it }
        return files.find { file ->
            val ext = file.name?.lowercase()?.substringAfterLast(".") ?: ""
            imageExtensions.contains(ext)
        }
    }
}
