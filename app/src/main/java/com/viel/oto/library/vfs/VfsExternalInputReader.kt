package com.viel.oto.library.vfs

import android.content.Context
import android.net.Uri
import java.io.InputStream

class VfsExternalInputReader(context: Context) {
    private val appContext = context.applicationContext

    fun openInputStream(uri: Uri): InputStream? =
        runCatching { appContext.contentResolver.openInputStream(uri) }.getOrNull()
}
