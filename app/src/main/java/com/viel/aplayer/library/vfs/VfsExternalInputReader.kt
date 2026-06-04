package com.viel.aplayer.library.vfs

import android.content.Context
import android.net.Uri
import java.io.InputStream

// Temporary content URIs returned from external picker are resolved inside VFS; the UI layer does not access ContentResolver file streams directly.
class VfsExternalInputReader(context: Context) {
    private val appContext = context.applicationContext

    fun openInputStream(uri: Uri): InputStream? =
        // Custom cover imports expose only the InputStream; content:// resolution details are kept inside the VFS boundary.
        runCatching { appContext.contentResolver.openInputStream(uri) }.getOrNull()
}
