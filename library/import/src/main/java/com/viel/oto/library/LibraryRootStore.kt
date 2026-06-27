package com.viel.oto.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.viel.oto.data.AppSettingsRepository
import com.viel.oto.data.dao.LibraryRootDao
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.data.webdav.WebDavCredentialStore
import com.viel.oto.data.webdav.webDavCredentialDataStore
import com.viel.oto.library.availability.AvailabilityChecker
import com.viel.oto.library.availability.LibraryRootAvailabilityUpdate
import com.viel.oto.library.root.AbsRootCredentialGateway
import com.viel.oto.library.vfs.sourceProvider.webdav.WebDavEndpointValidationException
import com.viel.oto.library.vfs.sourceProvider.webdav.WebDavEndpointValidationReason
import com.viel.oto.logger.SecureLog
import com.viel.oto.shared.policy.UnsafeNetworkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Persistent management of source roots.
 */
class LibraryRootStore(
    private val context: Context,
    private val rootDao: LibraryRootDao,
    private val availabilityChecker: AvailabilityChecker? = null,
    private val webDavCredentialStore: WebDavCredentialStore =
        WebDavCredentialStore(context.applicationContext.webDavCredentialDataStore),
    private val absRootCredentialGateway: AbsRootCredentialGateway? = null,
    private val appSettingsRepository: AppSettingsRepository
) {

    /**
     * Ingestion path registration.
     * Registers a local filesystem Uri or updates active authorization properties if already stored.
     */
    suspend fun addRoot(uri: Uri, displayName: String): LibraryRootEntity = withContext(Dispatchers.IO) {
        val normalizedUri = uri.normalizeScheme().toString()
        val resolvedDisplayName = displayName.ifBlank {
            resolveSafDisplayName(uri)
        }
        rootDao.getAllRootsOnce()
            .firstOrNull { existing -> existing.matchesSafTreeUri(normalizedUri) }
            ?.let { existing ->
                rootDao.updateRootGrantState(
                    id = existing.id,
                    displayName = resolvedDisplayName,
                    grantedAt = System.currentTimeMillis(),
                    status = AudiobookSchema.LibraryRootStatus.ACTIVE
                )
                return@withContext existing.copy(
                    displayName = resolvedDisplayName,
                    grantedAt = System.currentTimeMillis(),
                    status = AudiobookSchema.LibraryRootStatus.ACTIVE
                )
            }
        val root = LibraryRootEntity(
            id = UUID.randomUUID().toString(),
            sourceUri = normalizedUri,
            displayName = resolvedDisplayName,
            grantedAt = System.currentTimeMillis(),
            status = AudiobookSchema.LibraryRootStatus.ACTIVE
        )
        rootDao.insertRoot(root)
        root
    }

    suspend fun addWebDavRoot(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String = ""
    ): LibraryRootEntity = withContext(Dispatchers.IO) {
        val parsed = url.trim().toUri()
        val normalizedEndpoint = normalizeWebDavEndpoint(parsed)
        val normalizedBasePath = normalizeWebDavBasePath(basePath.ifBlank { parsed.path.orEmpty() })
        UnsafeNetworkPolicy.requireCleartextHttpAllowed(
            url = normalizedEndpoint,
            settings = appSettingsRepository.cachedSettings,
            operation = "WebDAV root registration"
        )
        val resolvedDisplayName = displayName.ifBlank {
            normalizedBasePath.toWebDavDisplayNameFallback()
        }
        val now = System.currentTimeMillis()
        rootDao.getAllRootsOnce()
            .firstOrNull { existing -> existing.isSameWebDavRoot(normalizedEndpoint, normalizedBasePath) }
            ?.let { existing ->
                val updated = stageWebDavCredentialAndPersistRoot(
                    username = username,
                    password = password,
                    root = LibraryRootLifecyclePolicy.markBindingRefreshed(
                        existing.copy(
                            displayName = resolvedDisplayName,
                            grantedAt = now
                        )
                    )
                )
                return@withContext updated
            }
        val root = LibraryRootEntity(
            id = UUID.randomUUID().toString(),
            sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
            sourceUri = normalizedEndpoint,
            basePath = normalizedBasePath,
            displayName = resolvedDisplayName,
            grantedAt = now,
            status = AudiobookSchema.LibraryRootStatus.ACTIVE
        )
        stageWebDavCredentialAndPersistRoot(
            username = username,
            password = password,
            root = root
        )
    }

    suspend fun addAbsRoot(
        credentialId: String,
        libraryId: String,
        displayName: String
    ): LibraryRootEntity = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = requireNotNull(requireAbsRootCredentialGateway().baseUrlFor(credentialId)) {
            "ABS_CREDENTIAL_NOT_FOUND:$credentialId"
        }
        UnsafeNetworkPolicy.requireCleartextHttpAllowed(
            url = normalizedBaseUrl,
            settings = appSettingsRepository.cachedSettings,
            operation = "ABS root registration"
        )
        val resolvedDisplayName = displayName.ifBlank { libraryId }
        val now = System.currentTimeMillis()
        val root = mergeAbsRoot(
            existingRoots = rootDao.getAllRootsOnce(),
            normalizedBaseUrl = normalizedBaseUrl,
            credentialId = credentialId,
            libraryId = libraryId,
            displayName = resolvedDisplayName,
            now = now
        )
        rootDao.insertRoot(root)
        root
    }

    /**
     * Updates every registered root before global synchronization.
     * Returns refreshed snapshots so sync callers can skip unavailable roots immediately after the persisted status has been reconciled.
     */
    suspend fun refreshPermissionStatuses(): List<LibraryRootAvailabilityUpdate> = withContext(Dispatchers.IO) {
        rootDao.getAllRootsOnce().map { root -> refreshRootStatusInternal(root) }
    }

    /**
     * Updates one root before a targeted synchronization.
     * Re-reads the root from Room and persists availability fields before returning the fresh model used by ABS sync tasks.
     */
    suspend fun refreshRootStatus(rootId: String): LibraryRootAvailabilityUpdate? = withContext(Dispatchers.IO) {
        rootDao.getRootById(rootId)?.let { root -> refreshRootStatusInternal(root) }
    }

    /**
     * Maps protocol reachability into root status columns.
     * Keeps LibraryRootStatus and detailed AvailabilityStatus synchronized so UI rows and sync guards observe the same source-of-truth state.
     */
    private suspend fun refreshRootStatusInternal(root: LibraryRootEntity): LibraryRootAvailabilityUpdate {
        val availability = requireAvailabilityChecker().checkRoot(root)
        val updatedRoot = LibraryRootLifecyclePolicy.applyAvailabilitySnapshot(root, availability)
        val status = updatedRoot.status
        if (root.status != status) {
            rootDao.updateRootStatus(root.id, status)
        }
        rootDao.updateRootAvailability(
            id = root.id,
            availabilityStatus = availability.status,
            checkedAt = availability.checkedAt,
            errorCode = availability.errorCode
        )
        return LibraryRootAvailabilityUpdate(root = updatedRoot, availability = availability)
    }

    private fun LibraryRootEntity.isSameWebDavRoot(candidateEndpoint: String, candidateBasePath: String): Boolean =
        sourceType == AudiobookSchema.LibrarySourceType.WEBDAV &&
            sourceUri == candidateEndpoint &&
            normalizeWebDavBasePath(basePath) == candidateBasePath

    private fun normalizeWebDavEndpoint(parsed: Uri): String {
        val scheme = parsed.scheme?.lowercase()
            ?: throw WebDavEndpointValidationException(WebDavEndpointValidationReason.MissingScheme)
        val authority = parsed.encodedAuthority
            ?: throw WebDavEndpointValidationException(WebDavEndpointValidationReason.MissingHost)
        if (!parsed.encodedUserInfo.isNullOrBlank()) {
            throw WebDavEndpointValidationException(WebDavEndpointValidationReason.UserInfoNotAllowed)
        }
        if (scheme != "http" && scheme != "https") {
            throw WebDavEndpointValidationException(WebDavEndpointValidationReason.UnsupportedScheme)
        }
        return "$scheme://$authority"
    }

    private fun normalizeWebDavBasePath(path: String): String =
        Uri.decode(path)
            .replace('\\', '/')
            .trim()
            .trim('/')
            .takeIf { it.isNotBlank() }
            ?.let { "/$it" }
            .orEmpty()

    /**
     * Converts normalized basePath into a compact label.
     *
     * Strips only the leading slash used for storage normalization while preserving nested path names such as "audiobooks/japanese".
     */
    private fun String.toWebDavDisplayNameFallback(): String =
        trim('/').ifBlank { "WebDAV" }

    /**
     * Derives a readable label from a tree URI.
     *
     * Converts document IDs such as "primary:Audiobooks" or "home:Documents" into the selected folder segment, falling back to a local-library label when the picker returns a storage root.
     */
    private fun resolveSafDisplayName(uri: Uri): String {
        val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }
            .getOrNull()
            ?.let(Uri::decode)
            .orEmpty()
        val selectedSegment = treeDocumentId
            .substringAfterLast(':', missingDelimiterValue = treeDocumentId)
            .replace('\\', '/')
            .trim()
            .trim('/')
            .substringAfterLast('/')
            .trim()
        return selectedSegment.ifBlank {
            "Local Library"
        }
    }

    /**
     * Relocate or re-authorize one local directory.
     * Reuses an already registered SAF root when the picker returns the same tree document ID, so edit-mode recovery follows the same identity rule as adding a local root.
     *
     * @param id Target root record ID
     * @param newUri The new folder tree URI
     * @return Updated library root record
     */
    suspend fun updateSafRoot(id: String, newUri: Uri): LibraryRootEntity = withContext(Dispatchers.IO) {
        val roots = rootDao.getAllRootsOnce()
        val existing = roots.firstOrNull { root -> root.id == id } ?: throw IllegalArgumentException("Root not found: $id")
        val normalizedUri = newUri.normalizeScheme().toString()
        val displayName = resolveSafDisplayName(newUri)
        val updated = resolveSafRootEditTarget(
            editingRoot = existing,
            roots = roots,
            normalizedUri = normalizedUri,
            displayName = displayName,
            now = System.currentTimeMillis()
        )
        if (updated.id == existing.id) {
            replaceSafReadPermission(existing.sourceUri, normalizedUri, newUri)
        } else {
            takeSafReadPermission(newUri)
        }
        rootDao.insertRoot(updated)
        updated
    }

    /**
     * Refreshes a SAF permission without dropping the old grant before a different new grant succeeds.
     * When Android returns the exact same tree URI, the old persisted grant must be released first so the picker result can re-authorize a revoked binding.
     */
    private fun replaceSafReadPermission(oldSourceUri: String, normalizedNewUri: String, newUri: Uri) {
        if (oldSourceUri == normalizedNewUri) {
            releaseSafReadPermission(oldSourceUri)
            takeSafReadPermission(newUri)
            return
        }
        takeSafReadPermission(newUri)
        releaseSafReadPermission(oldSourceUri)
    }

    /**
     * Persists the read grant returned by the system picker for the selected tree.
     */
    private fun takeSafReadPermission(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }

    /**
     * Releases stale SAF grants on a best-effort basis after a root is rebound.
     */
    private fun releaseSafReadPermission(sourceUri: String) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                sourceUri.toUri(),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            SecureLog.error("LibraryRootStore", "Failed to release old SAF permission for rootUri=$sourceUri", e)
        }
    }

    /**
     * Modify connection settings.
     * Modifies endpoints and updates encrypted password credentials for WebDAV connections.
     *
     * @param id Target root record ID
     * @param url Endpoint URL of the server
     * @param username Login username
     * @param password Login password
     * @param displayName Custom display label
     * @param basePath Remote mount sub-path
     * @return Updated library root record
     */
    suspend fun updateWebDavRoot(
        id: String,
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String
    ): LibraryRootEntity = withContext(Dispatchers.IO) {
        val existing = rootDao.getRootById(id) ?: throw IllegalArgumentException("Root not found: $id")
        val parsed = url.trim().toUri()
        val normalizedEndpoint = normalizeWebDavEndpoint(parsed)
        val normalizedBasePath = normalizeWebDavBasePath(basePath.ifBlank { parsed.path.orEmpty() })
        UnsafeNetworkPolicy.requireCleartextHttpAllowed(
            url = normalizedEndpoint,
            settings = appSettingsRepository.cachedSettings,
            operation = "WebDAV root update"
        )
        val resolvedDisplayName = displayName.ifBlank {
            normalizedBasePath.toWebDavDisplayNameFallback()
        }
        val updated = LibraryRootLifecyclePolicy.markBindingRefreshed(
            existing.copy(
                displayName = resolvedDisplayName,
                sourceUri = normalizedEndpoint,
                basePath = normalizedBasePath
            )
        )
        stageWebDavCredentialAndPersistRoot(
            username = username,
            password = password,
            root = updated
        )
    }

    /**
     * Modify reference bindings.
     * Updates matching display names and references to the chosen AudiobookShelf library.
     *
     * @param id Target root record ID
     * @param credentialId Server credential lookup key
     * @param libraryId Targeted mirror book library ID
     * @param displayName Custom display label
     * @return Updated library root record
     */
    suspend fun updateAbsRoot(
        id: String,
        credentialId: String,
        libraryId: String,
        displayName: String
    ): LibraryRootEntity = withContext(Dispatchers.IO) {
        val existing = rootDao.getRootById(id) ?: throw IllegalArgumentException("Root not found: $id")
        val normalizedBaseUrl = requireNotNull(requireAbsRootCredentialGateway().baseUrlFor(credentialId)) {
            "ABS_CREDENTIAL_NOT_FOUND:$credentialId"
        }
        UnsafeNetworkPolicy.requireCleartextHttpAllowed(
            url = normalizedBaseUrl,
            settings = appSettingsRepository.cachedSettings,
            operation = "ABS root update"
        )
        val resolvedDisplayName = displayName.ifBlank { libraryId }
        val updated = LibraryRootLifecyclePolicy.markBindingRefreshed(
            existing.copy(
                sourceUri = normalizedBaseUrl,
                basePath = libraryId,
                credentialId = credentialId,
                displayName = resolvedDisplayName
            )
        )
        rootDao.insertRoot(updated)
        updated
    }

    private suspend fun stageWebDavCredentialAndPersistRoot(
        username: String,
        password: String,
        root: LibraryRootEntity
    ): LibraryRootEntity {
        val previousCredentialId = root.credentialId
        val stagedCredential = webDavCredentialStore.save(username = username, password = password)
        val updated = root.copy(credentialId = stagedCredential.id)
        runCatching {
            rootDao.insertRoot(updated)
        }.onFailure {
            webDavCredentialStore.delete(stagedCredential.id)
        }.getOrThrow()
        if (previousCredentialId != null && previousCredentialId != stagedCredential.id) {
            webDavCredentialStore.delete(previousCredentialId)
        }
        return updated
    }

    /**
     * Returns the injected checker only for root availability refresh paths.
     */
    private fun requireAvailabilityChecker(): AvailabilityChecker =
        requireNotNull(availabilityChecker) { "LibraryRootStore requires AvailabilityChecker to refresh root statuses." }

    /**
     * Returns the injected ABS credential gateway only for ABS root mutation paths.
     */
    private fun requireAbsRootCredentialGateway(): AbsRootCredentialGateway =
        requireNotNull(absRootCredentialGateway) { "LibraryRootStore requires AbsRootCredentialGateway for ABS root mutations." }
}

internal fun mergeAbsRoot(
    existingRoots: List<LibraryRootEntity>,
    normalizedBaseUrl: String,
    credentialId: String,
    libraryId: String,
    displayName: String,
    now: Long,
    newRootId: String = UUID.randomUUID().toString()
): LibraryRootEntity {
    val existing = existingRoots.firstOrNull { root ->
        root.sourceType == AudiobookSchema.LibrarySourceType.ABS &&
            root.sourceUri == normalizedBaseUrl &&
            root.basePath == libraryId
    }
    return existing?.copy(
        displayName = displayName,
        credentialId = credentialId,
        grantedAt = now
    )?.let(LibraryRootLifecyclePolicy::markBindingRefreshed)
        ?: LibraryRootEntity(
            id = newRootId,
            sourceType = AudiobookSchema.LibrarySourceType.ABS,
            sourceUri = normalizedBaseUrl,
            basePath = libraryId,
            credentialId = credentialId,
            displayName = displayName,
            grantedAt = now,
            status = AudiobookSchema.LibraryRootStatus.ACTIVE
        )
}

/**
 * Selects the persisted SAF root that should receive an edit-mode tree grant.
 * Edit mode must reuse a registered root with the same Android tree document ID instead of blindly rebinding the row that launched the picker.
 */
internal fun resolveSafRootEditTarget(
    editingRoot: LibraryRootEntity,
    roots: List<LibraryRootEntity>,
    normalizedUri: String,
    displayName: String,
    now: Long
): LibraryRootEntity {
    val target = roots.firstOrNull { root -> root.matchesSafTreeUri(normalizedUri) } ?: editingRoot
    return LibraryRootLifecyclePolicy.markBindingRefreshed(
        target.copy(
            sourceUri = normalizedUri,
            displayName = displayName.ifBlank { target.displayName },
            grantedAt = now
        )
    )
}

/**
 * Compares SAF roots by persisted URI first and Android tree document ID second.
 * Android can return equivalent tree URIs with different string forms, so root identity cannot rely only on exact URI text.
 */
internal fun LibraryRootEntity.matchesSafTreeUri(candidateTreeUri: String): Boolean =
    sourceType == AudiobookSchema.LibrarySourceType.SAF &&
        (sourceUri == candidateTreeUri || treeDocumentId(sourceUri) == treeDocumentId(candidateTreeUri))

private fun treeDocumentId(sourceUri: String): String =
    Uri.decode(sourceUri).substringAfter("/tree/", missingDelimiterValue = sourceUri)
