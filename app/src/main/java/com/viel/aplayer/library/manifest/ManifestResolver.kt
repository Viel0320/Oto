package com.viel.aplayer.library.manifest

import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.net.URLDecoder

/**
 * 负责解析 Manifest 文件（cue/m3u8）中的相对路径。
 */
object ManifestResolver {

    /**
     * 解析相对路径。支持 URL 编码和简单的模糊匹配。
     */
    fun resolveRelativePath(parentDir: DocumentFile, relativePath: String): DocumentFile? {
        // 1. 处理可能存在的 URL 编码
        val decodedPath = try {
            URLDecoder.decode(relativePath, "UTF-8")
        } catch (e: Exception) {
            relativePath
        }

        val parts = decodedPath.split('/', '\\').filter { it.isNotBlank() }
        var current: DocumentFile? = parentDir

        for (part in parts) {
            // 2. 先尝试精确匹配
            var next = current?.findFile(part)
            
            // 3. 如果失败，尝试忽略大小写匹配（针对 Windows 用户习惯）
            if (next == null) {
                next = current?.listFiles()?.find { it.name?.equals(part, ignoreCase = true) == true }
            }
            
            current = next
            if (current == null) break
        }

        return if (current?.isFile == true) current else null
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