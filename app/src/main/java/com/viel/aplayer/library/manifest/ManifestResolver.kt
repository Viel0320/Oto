package com.viel.aplayer.library.manifest

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.net.URLDecoder

/**
 * 负责解析 Manifest 文件（cue/m3u8）中的相对路径。
 */
object ManifestResolver {

    // 详尽的中文注释：把清单条目规整成“当前清单同目录下的一个文件名”，让调用方可以直接用扫描阶段已有的文件索引匹配，避免再次触发 SAF findFile/listFiles。
    fun sameDirectoryFileName(relativePath: String): String? {
        val decodedPath = decodeRelativePath(relativePath)
        val parts = decodedPath.split('/', '\\').filter { it.isNotBlank() && it != "." }
        if (parts.size != 1 || parts.any { it == ".." }) return null
        return parts.single()
    }

    /**
     * 解析相对路径。支持 URL 编码和简单的模糊匹配。
     */
    fun resolveRelativePath(parentDir: DocumentFile, relativePath: String): DocumentFile? {
        val fileName = sameDirectoryFileName(relativePath) ?: return null

        // 详尽的中文注释：保留旧调用方的 SAF 解析能力，但路径规整统一走 sameDirectoryFileName，确保这里也不会意外放开子目录或上级目录引用。
        val exactMatch = parentDir.findFile(fileName)
        if (exactMatch?.isFile == true) return exactMatch

        return parentDir.listFiles()
            .find { file -> file.isFile && file.name?.equals(fileName, ignoreCase = true) == true }
    }

    // 详尽的中文注释：URL 解码集中在一个小函数里，清单闭包和旧 SAF 解析共用同一套文件名语义，避免两个调用点在空格、日文文件名或百分号编码上表现不一致。
    private fun decodeRelativePath(relativePath: String): String =
        try {
            URLDecoder.decode(relativePath, "UTF-8")
        } catch (e: Exception) {
            relativePath
        }

    /**
     * 获取相对于根目录的路径。
     */
    fun getDisplayPath(rootUri: Uri, fileUri: Uri): String {
        val rootPath = rootUri.path ?: ""
        val filePath = fileUri.path ?: ""
        return if (filePath.startsWith(rootPath)) {
            filePath.substring(rootPath.length).removePrefix("/")
        } else {
            fileUri.lastPathSegment ?: ""
        }
    }
}
