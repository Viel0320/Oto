// Platform Adaptation: Implement the abstract URI resolver using Android-specific FileProvider mechanisms.
package com.viel.aplayer.data.cover

import android.content.Context
import androidx.core.content.FileProvider
import com.viel.aplayer.data.cover.CoverUriResolver
import java.io.File

/**
 * Android Implementation of CoverUriResolver (AndroidCoverUriResolver)
 * 
 * Maps file coordinates to local content:// paths to guarantee read authorization 
 * for system media players.
 */
class AndroidCoverUriResolver(
    private val context: Context
) : CoverUriResolver {

    /**
     * Resolve absolute file path to a content URI string (To bridge sandboxed files to system widgets safely)
     * 
     * @param absolutePath The local absolute path of the target image file.
     * @return Content URI string mapped under APlayer provider configurations, or null if missing.
     */
    override fun toContentUri(absolutePath: String): String? {
        val file = File(absolutePath)
        // File Presence Check (Prevent FileProvider from crashing when locating non-existent directories)
        // Checks local VFS existence prior to generating content schema markers.
        return if (file.exists()) {
            FileProvider.getUriForFile(context, "com.viel.aplayer.fileprovider", file).toString()
        } else {
            null
        }
    }
}
