package com.viel.aplayer.application.download

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements

@OptIn(UnstableApi::class)
interface DownloadRuntimeGateway {
    /**
     * Submit one Media3 file-level request.
     * Requests must use BookFileEntity.id as both DownloadRequest.id and customCacheKey so manual-cache playback can find downloads.
     */
    fun addDownload(request: DownloadRequest)

    /**
     * Delete one Media3 download record and its cached bytes.
     * Book-level cleanup resolves individual file IDs before calling this narrow operation.
     */
    fun removeDownload(fileId: String)

    /**
     * Delegate global pause to Media3.
     * Book-level pause semantics are projected through metadata while Media3 owns the actual queue stop.
     */
    fun pauseDownloads()

    /**
     * Delegate global resume to Media3.
     * The first resume call is allowed to lazily resolve DownloadManager because it represents user-visible download work.
     */
    fun resumeDownloads()

    /**
     * Apply Media3's per-download pause marker.
     * Book-level pause and resume commands call this for each remote audio file so one book does not stop the whole queue.
     */
    fun setStopReason(fileId: String, reason: Int)

    /**
     * Apply the persisted WiFi policy to an already-created runtime.
     * Settings writes can skip this call when the runtime has not been resolved yet.
     */
    fun updateRequirements(wifiOnly: Boolean)
}

@OptIn(UnstableApi::class)
class DefaultDownloadRuntimeGateway(
    private val addDownloadCommand: (DownloadRequest) -> Unit,
    private val removeDownloadCommand: (String) -> Unit,
    private val pauseDownloadsCommand: () -> Unit,
    private val resumeDownloadsCommand: () -> Unit,
    private val setStopReasonCommand: (String, Int) -> Unit,
    private val updateRequirementsCommand: (Requirements) -> Unit
) : DownloadRuntimeGateway {
    override fun addDownload(request: DownloadRequest) {
        addDownloadCommand(request)
    }

    override fun removeDownload(fileId: String) {
        removeDownloadCommand(fileId)
    }

    override fun pauseDownloads() {
        pauseDownloadsCommand()
    }

    override fun resumeDownloads() {
        resumeDownloadsCommand()
    }

    override fun setStopReason(fileId: String, reason: Int) {
        setStopReasonCommand(fileId, reason)
    }

    override fun updateRequirements(wifiOnly: Boolean) {
        val requirements = if (wifiOnly) {
            Requirements(Requirements.NETWORK_UNMETERED)
        } else {
            Requirements(Requirements.NETWORK)
        }
        updateRequirementsCommand(requirements)
    }
}
