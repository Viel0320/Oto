package com.viel.oto.library.availability

import com.viel.oto.data.availability.FileAvailabilityProbe
import com.viel.oto.data.availability.FileAvailabilityResult
import com.viel.oto.data.entity.BookFileEntity

/**
 * FileAvailabilityProbe adapter backed by library source reachability checks.
 *
 * AvailabilityChecker remains the source-aware implementation for SAF, WebDAV, and ABS; this adapter exposes only
 * the normalized file-level result shape required by data persistence.
 */
class LibraryFileAvailabilityProbe(
    private val availabilityChecker: AvailabilityChecker
) : FileAvailabilityProbe {
    override suspend fun checkBookFile(file: BookFileEntity): FileAvailabilityResult =
        availabilityChecker.checkBookFile(file).toFileAvailabilityResult()

    override suspend fun checkBookFiles(files: List<BookFileEntity>): Map<String, FileAvailabilityResult> =
        availabilityChecker.checkBookFiles(files).mapValues { (_, result) ->
            result.toFileAvailabilityResult()
        }

    private fun AvailabilityResult.toFileAvailabilityResult(): FileAvailabilityResult =
        FileAvailabilityResult(
            status = status,
            errorCode = errorCode,
            message = message
        )
}
