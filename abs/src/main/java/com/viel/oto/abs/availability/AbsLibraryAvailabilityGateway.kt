package com.viel.oto.abs.availability

import com.viel.oto.abs.auth.AbsCredentialStore
import com.viel.oto.abs.net.AbsApiError
import com.viel.oto.abs.sync.AbsConnectionTester
import com.viel.oto.abs.vfs.AbsSourceProvider
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.library.availability.AbsAvailabilityGateway
import com.viel.oto.library.availability.AvailabilityResult
import com.viel.oto.shared.policy.UnsafeNetworkPolicy
import com.viel.oto.shared.model.AppSettings

/**
 * Checks ABS root and file availability behind the library availability seam.
 *
 * ABS owns the protocol calls and credential checks, while the library layer receives only
 * normalized AvailabilityResult values that can be persisted beside other source kinds.
 */
class AbsLibraryAvailabilityGateway(
    private val credentialStore: AbsCredentialStore,
    private val connectionTester: AbsConnectionTester,
    private val sourceProvider: AbsSourceProvider,
    private val settingsProvider: () -> AppSettings
) : AbsAvailabilityGateway {
    override suspend fun checkRoot(root: LibraryRootEntity): AvailabilityResult =
        runCatching {
            val credential = credentialStore.get(root.credentialId)
            if (credential == null) {
                AvailabilityResult(
                    status = AudiobookSchema.AvailabilityStatus.AUTH_FAILED,
                    errorCode = "MISSING_ABS_CREDENTIAL"
                )
            } else {
                UnsafeNetworkPolicy.requireCleartextHttpAllowed(
                    url = credential.baseUrl,
                    settings = settingsProvider(),
                    operation = "ABS root availability"
                )
                val connection = connectionTester.testConnection(credential.baseUrl, credential.token)
                val libraryStillExists = connection.bookLibraries.any { library -> library.id == root.basePath }
                if (libraryStillExists) {
                    AvailabilityResult(status = AudiobookSchema.AvailabilityStatus.AVAILABLE)
                } else {
                    AvailabilityResult(
                        status = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                        errorCode = AudiobookSchema.AvailabilityStatus.NOT_FOUND.name,
                        message = "ABS library not found"
                    )
                }
            }
        }.getOrElse { error -> error.toAvailabilityResult() }

    override suspend fun checkBookFile(root: LibraryRootEntity, file: BookFileEntity): AvailabilityResult =
        runCatching {
            if (sourceProvider.checkReadable(root, file.sourcePath)) {
                AvailabilityResult(status = AudiobookSchema.AvailabilityStatus.AVAILABLE)
            } else {
                AvailabilityResult(
                    status = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                    errorCode = AudiobookSchema.AvailabilityStatus.NOT_FOUND.name
                )
            }
        }.getOrElse { error -> error.toAvailabilityResult() }

    private fun Throwable.toAvailabilityResult(): AvailabilityResult {
        val absError = this as? AbsApiError
        return AvailabilityResult(
            status = absError?.availabilityStatus ?: AudiobookSchema.AvailabilityStatus.UNKNOWN,
            errorCode = absError?.code ?: this::class.java.simpleName,
            message = localizedMessage ?: message
        )
    }
}
