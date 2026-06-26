package com.viel.oto.application.download

/**
 * Tracks whether the app-owned Media3 download runtime has been created.
 *
 * Settings commands use this seam to update runtime download requirements only when the runtime is
 * alive, while the app composition root remains responsible for flipping the flag around the actual
 * Media3 DownloadManager lifecycle.
 */
class DownloadRuntimeInitializedFlag {
    @Volatile
    var value: Boolean = false
}
