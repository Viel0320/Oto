package com.viel.aplayer.library.vfs

import android.content.Context
import android.net.Uri
import java.io.InputStream

// 详尽的中文注释：外部选择器返回的临时 content Uri 也集中在 VFS 边界打开，UI 层不直接触碰 ContentResolver 文件流。
class VfsExternalInputReader(context: Context) {
    private val appContext = context.applicationContext

    fun openInputStream(uri: Uri): InputStream? =
        // 为每一次改动添加详尽的中文注释：自定义封面导入只暴露 InputStream 结果，具体 content:// 读取细节留在 VFS 边界内。
        runCatching { appContext.contentResolver.openInputStream(uri) }.getOrNull()
}
