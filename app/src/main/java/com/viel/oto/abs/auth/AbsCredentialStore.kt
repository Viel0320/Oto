package com.viel.oto.abs.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.Moshi
import com.viel.oto.abs.net.AbsUrlResolver
import com.viel.oto.logger.AbsAuthLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.absCredentialDataStore: DataStore<Preferences> by preferencesDataStore(name = "abs_credentials")

/**
 * Responsible for storing sensitive connection tokens and server parameters safely.
 *
 * Design Constraints:
 * 1. Room DB references and outer UI layers query using `credentialId` and never query tokens directly.
 * 2. Backed by DataStore for now; the implementation can be upgraded to Keystore-encrypted storage seamlessly.
 */
class AbsCredentialStore internal constructor(
    private val dataStore: DataStore<Preferences>,
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
        AbsAuthLogger.logCredentialGet(credentialId = credentialId, found = credential != null)
        return credential
    }

    suspend fun delete(credentialId: String?) {
        if (credentialId.isNullOrBlank()) return
        dataStore.edit { preferences ->
            preferences.remove(credentialKey(credentialId))
        }
        AbsAuthLogger.logCredentialDelete(credentialId)
    }

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
        return AbsUrlResolver.resolveBaseUrl(baseUrl).toString().trimEnd('/')
    }

    private fun credentialKey(credentialId: String): Preferences.Key<String> =
        stringPreferencesKey("abs_credential.$credentialId")

    companion object {
        internal fun createForTesting(dataStore: DataStore<Preferences>): AbsCredentialStore =
            AbsCredentialStore(dataStore = dataStore)
    }
}
