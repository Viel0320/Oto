package com.viel.aplayer.abs.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.Moshi
import com.viel.aplayer.logger.AbsAuthLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.absCredentialDataStore: DataStore<Preferences> by preferencesDataStore(name = "abs_credentials")

/**
 * ABS Credential Store (Responsible for storing sensitive connection tokens and server parameters safely)
 * 
 * Design Constraints:
 * 1. Room DB references and outer UI layers query using `credentialId` and never query tokens directly.
 * 2. Backed by DataStore for now; the implementation can be upgraded to Keystore-encrypted storage seamlessly.
 */
class AbsCredentialStore private constructor(
    private val dataStore: DataStore<Preferences>,
    // Pure Codegen: Instantiate Moshi without reflection support since all DTOs have generated adapters.
    private val moshi: Moshi = Moshi.Builder().build()
) {
    private val adapter = moshi.adapter(AbsCredential::class.java)

    suspend fun save(
        baseUrl: String,
        token: String,
        userId: String? = null,
        username: String? = null,
        serverKey: String? = null,
        credentialId: String = UUID.randomUUID().toString()
    ): AbsCredential {
        val credential = AbsCredential(
            id = credentialId,
            baseUrl = normalizeBaseUrl(baseUrl),
            token = token,
            userId = userId,
            username = username,
            serverKey = serverKey
        )
        dataStore.edit { preferences ->
            preferences[credentialKey(credential.id)] = adapter.toJson(credential)
        }
        // Logging Credential Persistence (Log authentication details to help trace initialization issues)
        // Strictly logs non-sensitive parameters (credentialId, baseUrl, userId, username), omitting raw tokens.
        AbsAuthLogger.logCredentialSave(
            baseUrl = credential.baseUrl,
            credentialId = credential.id,
            userId = credential.userId,
            username = credential.username
        )
        return credential
    }

    suspend fun get(credentialId: String?): AbsCredential? {
        if (credentialId.isNullOrBlank()) return null
        val credential = dataStore.data
            .map { preferences -> preferences[credentialKey(credentialId)] }
            .first()
            ?.let { raw ->
                runCatching { adapter.fromJson(raw) }
                    .getOrNull()
            }
        // Logging Retrieval Outcome (Tracks query cache hits to distinguish invalid ID calls from corrupted records)
        AbsAuthLogger.logCredentialGet(credentialId = credentialId, found = credential != null)
        return credential
    }

    suspend fun delete(credentialId: String?) {
        if (credentialId.isNullOrBlank()) return
        dataStore.edit { preferences ->
            preferences.remove(credentialKey(credentialId))
        }
        // Logging Deletion Events (Logs critical cleanup operations to aid in tracing unauthorized request events)
        AbsAuthLogger.logCredentialDelete(credentialId)
    }

    // Token Refresh Persistence (Updates only the bearer token while preserving the credential identity and server metadata)
    // ABS token refresh must not create a new credential ID because library roots already reference the existing record.
    suspend fun updateToken(credentialId: String, newToken: String): AbsCredential? {
        val existing = get(credentialId) ?: return null
        return save(
            baseUrl = existing.baseUrl,
            token = newToken,
            userId = existing.userId,
            username = existing.username,
            serverKey = existing.serverKey,
            credentialId = credentialId
        )
    }

    fun normalizeBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        require(trimmed.isNotBlank()) { "ABS baseUrl 不能为空" }
        val noTrailingSlash = trimmed.trimEnd('/')
        return noTrailingSlash.ifBlank { trimmed }
    }

    private fun credentialKey(credentialId: String): Preferences.Key<String> =
        stringPreferencesKey("abs_credential.$credentialId")

    companion object {
        @Volatile
        private var INSTANCE: AbsCredentialStore? = null

        // JVM Unit Test Constructor (Exposes initialization factory to run test scripts without Android contexts)
        internal fun createForTesting(dataStore: DataStore<Preferences>): AbsCredentialStore =
            AbsCredentialStore(dataStore = dataStore)

        fun getInstance(context: Context): AbsCredentialStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AbsCredentialStore(context.applicationContext.absCredentialDataStore).also { INSTANCE = it }
            }
        }

        // Test Singleton Reset (Exposes state cleaner to prevent test instances from sharing the cached DataStore)
        internal fun resetForTesting() {
            INSTANCE = null
        }
    }
}
