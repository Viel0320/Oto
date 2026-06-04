package com.viel.aplayer.library

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.availability.AvailabilityChecker
import com.viel.aplayer.library.vfs.sourceProvider.LibrarySourceKind
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavCredentialStore
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
    private val absCredentialStoreOverride: AbsCredentialStore? = null
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

    /**
     * Add New Storage Root (Ingestion path registration)
     * Registers a local filesystem Uri or updates active authorization properties if already stored.
     */
    suspend fun addRoot(uri: Uri, displayName: String): LibraryRootEntity = withContext(Dispatchers.IO) {
        val normalizedUri = uri.normalizeScheme().toString()
        rootDao.getAllRootsOnce()
            .firstOrNull { existing -> existing.isSameRoot(normalizedUri) }
            ?.let { existing ->
                // Re-selecting a stored root refreshes its grant state instead of inserting a duplicate row.
                rootDao.updateRootGrantState(
                    id = existing.id,
                    displayName = displayName,
                    grantedAt = System.currentTimeMillis(),
                    status = AudiobookSchema.LibraryRootStatus.ACTIVE
                )
                return@withContext existing.copy(
                    displayName = displayName,
                    grantedAt = System.currentTimeMillis(),
                    status = AudiobookSchema.LibraryRootStatus.ACTIVE
                )
            }
        val root = LibraryRootEntity(
            id = UUID.randomUUID().toString(),
            // Unified URI Mapping (Uniform path parameter abstraction)
            // Assigns tree URIs to sourceUri, allowing WebDAV protocols to share identical location fields later.
            sourceUri = normalizedUri,
            displayName = displayName,
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
        val resolvedDisplayName = displayName.ifBlank {
            // Label Fallback Interpolation (UI rendering safety guard)
            // Interpolates host address and base folder path if no display name is defined by the user.
            buildString {
                append(parsed.host ?: normalizedEndpoint)
                if (normalizedBasePath.isNotBlank()) append(normalizedBasePath)
            }
        }
        val now = System.currentTimeMillis()
        rootDao.getAllRootsOnce()
            .firstOrNull { existing -> existing.isSameWebDavRoot(normalizedEndpoint, normalizedBasePath) }
            ?.let { existing ->
                val credential = webDavCredentialStore.save(
                    username = username,
                    password = password,
                    credentialId = existing.credentialId ?: UUID.randomUUID().toString()
                )
                val updated = existing.copy(
                    displayName = resolvedDisplayName,
                    credentialId = credential.id,
                    grantedAt = now,
                    status = AudiobookSchema.LibraryRootStatus.ACTIVE,
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.UNKNOWN,
                    lastAvailabilityCheckedAt = 0L,
                    lastAvailabilityErrorCode = null
                )
                // WebDAV Ingest Deduplication (Record update strategy)
                // Overwrites credentials and labels rather than creating duplicate rows if the same WebDAV target is added.
                rootDao.insertRoot(updated)
                return@withContext updated
            }
        val credential = webDavCredentialStore.save(username = username, password = password)
        val root = LibraryRootEntity(
            id = UUID.randomUUID().toString(),
            sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
            sourceUri = normalizedEndpoint,
            basePath = normalizedBasePath,
            credentialId = credential.id,
            displayName = resolvedDisplayName,
            grantedAt = now,
            status = AudiobookSchema.LibraryRootStatus.ACTIVE
        )
        // Scanner availability: Permits SourceInventoryScanner to sweep files immediately after the record commits.
        rootDao.insertRoot(root)
        root
    }

    suspend fun addAbsRoot(
        credentialId: String,
        libraryId: String,
        displayName: String
    ): LibraryRootEntity = withContext(Dispatchers.IO) {
        val credential = requireNotNull(absCredentialStore.get(credentialId)) {
            "ABS 凭据不存在: $credentialId"
        }
        val normalizedBaseUrl = credential.baseUrl
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

    suspend fun refreshPermissionStatuses() = withContext(Dispatchers.IO) {
        // Startup and settings entry both reconcile persisted SAF grants with stored root status.
        rootDao.getAllRootsOnce().forEach { root ->
            val availability = availabilityChecker.checkRoot(root)
            val status = if (availability.isAvailable) {
                AudiobookSchema.LibraryRootStatus.ACTIVE
            } else if (LibrarySourceKind.from(root.sourceType) == LibrarySourceKind.SAF) {
                AudiobookSchema.LibraryRootStatus.REVOKED
            } else {
                // Remote Error Flagging (Diagnostics routing policy)
                // Maps remote checkouts to ERROR states, enabling UI lists to separate network timeouts from local permission revocations.
                AudiobookSchema.LibraryRootStatus.ERROR
            }
            if (root.status != status) {
                rootDao.updateRootStatus(root.id, status)
            }
            rootDao.updateRootAvailability(
                id = root.id,
                availabilityStatus = availability.status,
                checkedAt = availability.checkedAt,
                errorCode = availability.errorCode
            )
        }
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
            ?: throw IllegalArgumentException("WebDAV URL 缺少协议")
        val authority = parsed.encodedAuthority
            ?: throw IllegalArgumentException("WebDAV URL 缺少主机")
        require(scheme == "http" || scheme == "https") { "WebDAV URL 仅支持 http/https" }
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

    private fun treeDocumentId(sourceUri: String): String =
        Uri.decode(sourceUri).substringAfter("/tree/", missingDelimiterValue = sourceUri)

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
            val oldUri = Uri.parse(existing.sourceUri)
            context.contentResolver.releasePersistableUriPermission(
                oldUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            android.util.Log.e("LibraryRootStore", "Failed to release old SAF permission for root $id", e)
        }
        context.contentResolver.takePersistableUriPermission(
            newUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        val normalizedUri = newUri.normalizeScheme().toString()
        val displayName = try {
            Uri.decode(normalizedUri).substringAfterLast(":")
        } catch (_: Exception) {
            existing.displayName
        }
        val updated = existing.copy(
            sourceUri = normalizedUri,
            displayName = if (displayName.isNotBlank()) displayName else existing.displayName,
            status = AudiobookSchema.LibraryRootStatus.ACTIVE,
            availabilityStatus = AudiobookSchema.AvailabilityStatus.UNKNOWN,
            lastAvailabilityCheckedAt = 0L,
            lastAvailabilityErrorCode = null
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
        val resolvedDisplayName = displayName.ifBlank {
            buildString {
                append(parsed.host ?: normalizedEndpoint)
                if (normalizedBasePath.isNotBlank()) append(normalizedBasePath)
            }
        }
        val credentialId = existing.credentialId ?: UUID.randomUUID().toString()
        webDavCredentialStore.save(
            username = username,
            password = password,
            credentialId = credentialId
        )
        val updated = existing.copy(
            displayName = resolvedDisplayName,
            sourceUri = normalizedEndpoint,
            basePath = normalizedBasePath,
            credentialId = credentialId,
            status = AudiobookSchema.LibraryRootStatus.ACTIVE,
            availabilityStatus = AudiobookSchema.AvailabilityStatus.UNKNOWN,
            lastAvailabilityCheckedAt = 0L,
            lastAvailabilityErrorCode = null
        )
        rootDao.insertRoot(updated)
        updated
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
            "ABS 凭据不存在: $credentialId"
        }
        val resolvedDisplayName = displayName.ifBlank { libraryId }
        val updated = existing.copy(
            sourceUri = credential.baseUrl,
            basePath = libraryId,
            credentialId = credentialId,
            displayName = resolvedDisplayName,
            status = AudiobookSchema.LibraryRootStatus.ACTIVE,
            availabilityStatus = AudiobookSchema.AvailabilityStatus.UNKNOWN,
            lastAvailabilityCheckedAt = 0L,
            lastAvailabilityErrorCode = null
        )
        rootDao.insertRoot(updated)
        updated
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
        grantedAt = now,
        status = AudiobookSchema.LibraryRootStatus.ACTIVE,
        availabilityStatus = AudiobookSchema.AvailabilityStatus.UNKNOWN,
        lastAvailabilityCheckedAt = 0L,
        lastAvailabilityErrorCode = null
    )
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
