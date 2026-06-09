package com.viel.aplayer.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.availability.AvailabilityChecker
import com.viel.aplayer.library.availability.LibraryRootAvailabilityUpdate
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavEndpointValidationException
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavEndpointValidationReason
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavCredentialStore
import com.viel.aplayer.logger.SecureLog
import com.viel.aplayer.network.UnsafeNetworkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Library Roots Ingestion Store (Persistent management of source roots)
 */
class LibraryRootStore(
    private val context: Context,
    private val rootDaoOverride: com.viel.aplayer.data.dao.LibraryRootDao? = null,
    private val availabilityCheckerOverride: AvailabilityChecker? = null,
    private val webDavCredentialStoreOverride: WebDavCredentialStore? = null,
    private val absCredentialStoreOverride: AbsCredentialStore? = null,
    private val appSettingsRepositoryOverride: AppSettingsRepository? = null
) {
    private val rootDao by lazy { rootDaoOverride ?: AppDatabase.getInstance(context).libraryRootDao() }
    // Unified Availability Scanner (Access checking facade)
    // Resolves folder accessibility states, reusing identical state schemas across SAF and WebDAV protocols.
    private val availabilityChecker by lazy { availabilityCheckerOverride ?: AvailabilityChecker(context.applicationContext) }
    // WebDAV Credential Store (Local password manager)
    // Updates credentials inside secure memory databases, storing only references to credentialId inside SQLite.
    private val webDavCredentialStore by lazy { webDavCredentialStoreOverride ?: WebDavCredentialStore(context.applicationContext) }
    // ABS Token Scoping Boundary (Credential isolation support)
    // Stores only reference IDs in root models, preventing auth tokens from persisting to raw library tables.
    private val absCredentialStore by lazy { absCredentialStoreOverride ?: AbsCredentialStore.getInstance(context.applicationContext) }
    // Unsafe Network Settings Source (Global compatibility switch owner)
    // Root registration reads the cached settings so endpoint validation matches runtime WebDAV, ABS, and playback policy without introducing per-root exceptions.
    private val appSettingsRepository by lazy { appSettingsRepositoryOverride ?: AppSettingsRepository.getInstance(context.applicationContext) }

    /**
     * Add New Storage Root (Ingestion path registration)
     * Registers a local filesystem Uri or updates active authorization properties if already stored.
     */
    suspend fun addRoot(uri: Uri, displayName: String): LibraryRootEntity = withContext(Dispatchers.IO) {
        val normalizedUri = uri.normalizeScheme().toString()
        val resolvedDisplayName = displayName.ifBlank {
            // SAF Display Name Resolution (Use the user-selected tree folder name)
            // The system picker returns a tree URI rather than a readable label, so the store derives a stable folder label before persisting the root.
            resolveSafDisplayName(uri)
        }
        rootDao.getAllRootsOnce()
            .firstOrNull { existing -> existing.isSameRoot(normalizedUri) }
            ?.let { existing ->
                // Re-selecting a stored root refreshes its grant state instead of inserting a duplicate row.
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
            // Unified URI Mapping (Uniform path parameter abstraction)
            // Assigns tree URIs to sourceUri, allowing WebDAV protocols to share identical location fields later.
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
        // WebDAV Root Registration Policy (Reject cleartext roots before credentials are persisted)
        // Root creation is the earliest durable boundary, so HTTP endpoints require the same explicit global opt-in as connection tests and VFS reads.
        UnsafeNetworkPolicy.requireCleartextHttpAllowed(
            url = normalizedEndpoint,
            settings = appSettingsRepository.cachedSettings,
            operation = "WebDAV root registration"
        )
        val resolvedDisplayName = displayName.ifBlank {
            // WebDAV Display Name Fallback (Prefer the configured remote library path)
            // When the user leaves the custom name empty, the root label mirrors basePath instead of mixing host and path into a longer technical endpoint label.
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
        val credential = requireNotNull(absCredentialStore.get(credentialId)) {
            "ABS_CREDENTIAL_NOT_FOUND:$credentialId"
        }
        val normalizedBaseUrl = credential.baseUrl
        // ABS Root Registration Policy (Reject cleartext server roots before library rows are persisted)
        // This keeps ABS catalog roots aligned with login and API transport policy instead of storing roots that runtime requests would block.
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
     * Refresh All Root Statuses (Updates every registered root before global synchronization)
     * Returns refreshed snapshots so sync callers can skip unavailable roots immediately after the persisted status has been reconciled.
     */
    suspend fun refreshPermissionStatuses(): List<LibraryRootAvailabilityUpdate> = withContext(Dispatchers.IO) {
        // Startup and settings entry both reconcile persisted SAF grants with stored root status.
        rootDao.getAllRootsOnce().map { root -> refreshRootStatusInternal(root) }
    }

    /**
     * Refresh Single Root Status (Updates one root before a targeted synchronization)
     * Re-reads the root from Room and persists availability fields before returning the fresh model used by ABS sync tasks.
     */
    suspend fun refreshRootStatus(rootId: String): LibraryRootAvailabilityUpdate? = withContext(Dispatchers.IO) {
        rootDao.getRootById(rootId)?.let { root -> refreshRootStatusInternal(root) }
    }

    /**
     * Persist Availability Snapshot (Maps protocol reachability into root status columns)
     * Keeps LibraryRootStatus and detailed AvailabilityStatus synchronized so UI rows and sync guards observe the same source-of-truth state.
     */
    private suspend fun refreshRootStatusInternal(root: LibraryRootEntity): LibraryRootAvailabilityUpdate {
        val availability = availabilityChecker.checkRoot(root)
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

    private fun LibraryRootEntity.isSameRoot(candidateTreeUri: String): Boolean =
        // URI-Based Deduplication (Legacy schema decoupling)
        // Asserts similarity through sourceUri, replacing legacy root fields from schema versions.
        sourceType == AudiobookSchema.LibrarySourceType.SAF &&
            (sourceUri == candidateTreeUri || treeDocumentId(sourceUri) == treeDocumentId(candidateTreeUri))

    private fun LibraryRootEntity.isSameWebDavRoot(candidateEndpoint: String, candidateBasePath: String): Boolean =
        // WebDAV Identity Uniqueness (Composite target matching)
        // Matches unique targets using sourceType, normalized endpoint, and basePath to permit multiple library setups on one server.
        sourceType == AudiobookSchema.LibrarySourceType.WEBDAV &&
            sourceUri == candidateEndpoint &&
            normalizeWebDavBasePath(basePath) == candidateBasePath

    private fun normalizeWebDavEndpoint(parsed: Uri): String {
        val scheme = parsed.scheme?.lowercase()
            ?: throw WebDavEndpointValidationException(WebDavEndpointValidationReason.MissingScheme)
        val authority = parsed.encodedAuthority
            ?: throw WebDavEndpointValidationException(WebDavEndpointValidationReason.MissingHost)
        if (!parsed.encodedUserInfo.isNullOrBlank()) {
            // WebDAV Userinfo Rejection (Keep credentials in credential storage, not endpoint URLs)
            // Endpoint URLs can flow into persistent root rows and diagnostics, so Basic Auth material must stay in WebDavCredentialStore instead of URI authority.
            throw WebDavEndpointValidationException(WebDavEndpointValidationReason.UserInfoNotAllowed)
        }
        if (scheme != "http" && scheme != "https") {
            throw WebDavEndpointValidationException(WebDavEndpointValidationReason.UnsupportedScheme)
        }
        // Endpoint Scheme Split (Unified domain partitioning)
        // Restricts sourceUri to protocol scheme and host address, shifting paths into the basePath field.
        return "$scheme://$authority"
    }

    private fun normalizeWebDavBasePath(path: String): String =
        // Path Format Uniformity (URL canonicalization helper)
        // Standardizes sub-paths to start with / and end without trailing slashes, mapping server root directory to an empty string.
        Uri.decode(path)
            .replace('\\', '/')
            .trim()
            .trim('/')
            .takeIf { it.isNotBlank() }
            ?.let { "/$it" }
            .orEmpty()

    /**
     * WebDAV Display Name Fallback (Converts normalized basePath into a compact label)
     *
     * Strips only the leading slash used for storage normalization while preserving nested path names such as "audiobooks/japanese".
     */
    private fun String.toWebDavDisplayNameFallback(): String =
        trim('/').ifBlank { "WebDAV" }

    private fun treeDocumentId(sourceUri: String): String =
        Uri.decode(sourceUri).substringAfter("/tree/", missingDelimiterValue = sourceUri)

    /**
     * SAF Display Name Resolver (Derives a readable label from a tree URI)
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
            // SAF Root Label Fallback (Avoid empty labels for storage-root selections)
            // Some providers expose the root as "primary:" with no child folder segment, so the UI receives a readable generic local source name.
            "Local Library"
        }
    }

    /**
     * Update SAF root configuration (Relocate local directory)
     * Replaces permission trees and updates source URI for existing SAF root records.
     *
     * @param id Target root record ID
     * @param newUri The new folder tree URI
     * @return Updated library root record
     */
    suspend fun updateSafRoot(id: String, newUri: Uri): LibraryRootEntity = withContext(Dispatchers.IO) {
        val existing = rootDao.getRootById(id) ?: throw IllegalArgumentException("Root not found: $id")
        try {
            val oldUri = existing.sourceUri.toUri()
            context.contentResolver.releasePersistableUriPermission(
                oldUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            // Release Error Boundary (Sanitize SAF permission release failures)
            // SAF provider exceptions can include tree URI details, so retained errors must flow through SecureLog.
            SecureLog.error("LibraryRootStore", "Failed to release old SAF permission for root $id", e)
        }
        context.contentResolver.takePersistableUriPermission(
            newUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        val normalizedUri = newUri.normalizeScheme().toString()
        // SAF Relocation Display Name Resolution (Reuse the same selected-folder label logic as new roots)
        // Keeping add and update paths aligned prevents relocated libraries from showing raw tree URI fragments.
        val displayName = resolveSafDisplayName(newUri).ifBlank { existing.displayName }
        val updated = LibraryRootLifecyclePolicy.markBindingRefreshed(
            existing.copy(
                sourceUri = normalizedUri,
                displayName = if (displayName.isNotBlank()) displayName else existing.displayName
            )
        )
        rootDao.insertRoot(updated)
        updated
    }

    /**
     * Update WebDAV root configuration (Modify connection settings)
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
        // WebDAV Root Update Policy (Apply cleartext validation before replacing stored endpoint data)
        // Edits can move a previously safe HTTPS root to HTTP, so updates must be checked just like first-time registration.
        UnsafeNetworkPolicy.requireCleartextHttpAllowed(
            url = normalizedEndpoint,
            settings = appSettingsRepository.cachedSettings,
            operation = "WebDAV root update"
        )
        val resolvedDisplayName = displayName.ifBlank {
            // WebDAV Update Display Name Fallback (Keep edit behavior aligned with new root creation)
            // Empty custom names resolve to the remote basePath so Settings and Detail use the same user-facing library label.
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
     * Update ABS root configuration (Modify reference bindings)
     * Updates matching display names and references to the chosen Audiobookshelf library.
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
        val credential = requireNotNull(absCredentialStore.get(credentialId)) {
            "ABS_CREDENTIAL_NOT_FOUND:$credentialId"
        }
        // ABS Root Update Policy (Apply cleartext validation before rebinding a root to a new server credential)
        // The selected credential owns the server URL, so updates must reject HTTP unless the global cleartext setting is enabled.
        UnsafeNetworkPolicy.requireCleartextHttpAllowed(
            url = credential.baseUrl,
            settings = appSettingsRepository.cachedSettings,
            operation = "ABS root update"
        )
        val resolvedDisplayName = displayName.ifBlank { libraryId }
        val updated = LibraryRootLifecyclePolicy.markBindingRefreshed(
            existing.copy(
                sourceUri = credential.baseUrl,
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
            // Credential Binding Commit (Only bind staged credentials after the root row can be persisted)
            // Room is the durable owner of credential references, so failed root writes must leave the previously bound credential untouched.
            rootDao.insertRoot(updated)
        }.onFailure {
            // Staged Credential Rollback (Remove credentials that never became reachable from Room)
            // This prevents failed WebDAV edits or deduplicated additions from leaking unbound username/password records.
            webDavCredentialStore.delete(stagedCredential.id)
        }.getOrThrow()
        if (previousCredentialId != null && previousCredentialId != stagedCredential.id) {
            // Previous Credential Retirement (Remove the replaced WebDAV secret after the new binding commits)
            // WebDAV roots own independent credential IDs, so successful rebinds should not leave stale username/password records behind.
            webDavCredentialStore.delete(previousCredentialId)
        }
        return updated
    }
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
            // ABS Endpoint Partitioning (Multi-library abstraction support)
            // Binds baseUrl to sourceUri and moves libraryId to basePath, allowing host reuse across libraries.
            sourceUri = normalizedBaseUrl,
            basePath = libraryId,
            credentialId = credentialId,
            displayName = displayName,
            grantedAt = now,
            status = AudiobookSchema.LibraryRootStatus.ACTIVE
        )
}
