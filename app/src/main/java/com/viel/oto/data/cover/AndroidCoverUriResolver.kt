package com.viel.oto.data.cover

import android.content.Context
import androidx.core.content.FileProvider
import java.io.File

/**
 * AndroidCoverUriResolver.
 *
 * Maps file coordinates to local content:// paths to guarantee read authorization
 * for system media players.
 */
class AndroidCoverUriResolver(
    private val context: Context
) : CoverUriResolver {

    /**
     * To bridge sandboxed files to system widgets safely.
     *
     * @param absolutePath The local absolute path of the target image file.
     * @return Content URI string mapped under Oto provider configurations, or null if missing.
     */
    override fun toContentUri(absolutePath: String): String? {
        val file = File(absolutePath)
        return if (file.exists()) {
            FileProvider.getUriForFile(context, "com.viel.oto.fileprovider", file).toString()
        } else {
            null
        }
    }
}
