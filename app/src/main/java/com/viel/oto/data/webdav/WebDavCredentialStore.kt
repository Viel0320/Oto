package com.viel.oto.data.webdav

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.util.UUID

private const val WEB_DAV_CREDENTIALS_STORE_NAME = "webdav_credentials"

/**
 * Process-wide WebDAV credential DataStore with a one-time bridge from the legacy preferences file.
 *
 * The migration keeps installed users' existing WebDAV roots usable after the storage backend moves
 * away from SharedPreferences, while new writes land only in the DataStore preferences file.
 */
internal val Context.webDavCredentialDataStore: DataStore<Preferences> by preferencesDataStore(
    name = WEB_DAV_CREDENTIALS_STORE_NAME,
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, WEB_DAV_CREDENTIALS_STORE_NAME))
    }
)

data class WebDavCredential(
    val id: String,
    val username: String,
    val password: String
)

/**
 * Persists WebDAV credentials in a dedicated Preferences DataStore.
 *
 * Password values remain Base64 encoded only to preserve the legacy on-disk shape during migration;
 * callers must not treat that encoding as encryption or as a broader credential-security boundary.
 */
class WebDavCredentialStore internal constructor(
    private val dataStore: DataStore<Preferences>
) {

    /**
     * Stores one credential pair under a stable credential id.
     *
     * WebDAV roots keep only the id in Room, so this method writes username and password atomically
     * before the root row is committed by the caller.
     */
    suspend fun save(
        username: String,
        password: String,
        credentialId: String = UUID.randomUUID().toString()
    ): WebDavCredential {
        val encodedPassword = Base64.encodeToString(password.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val credential = WebDavCredential(
            id = credentialId,
            username = username,
            password = password
        )
        dataStore.edit { preferences ->
            preferences[usernameKey(credentialId)] = username
            preferences[passwordKey(credentialId)] = encodedPassword
            preferences.remove(legacyAllowInsecureTlsKey(credentialId))
        }
        return credential
    }

    /**
     * Reads credentials by id without exposing DataStore preferences to application or UI callers.
     */
    suspend fun get(credentialId: String?): WebDavCredential? {
        if (credentialId.isNullOrBlank()) return null
        val preferences = dataStore.data.first()
        val username = preferences[usernameKey(credentialId)] ?: return null
        val encodedPassword = preferences[passwordKey(credentialId)] ?: return null
        val password = runCatching {
            String(Base64.decode(encodedPassword, Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrDefault(encodedPassword)
        return WebDavCredential(
            id = credentialId,
            username = username,
            password = password
        )
    }

    /**
     * Removes every persisted field for one credential id after the owning root is replaced or deleted.
     */
    suspend fun delete(credentialId: String?) {
        if (credentialId.isNullOrBlank()) return
        dataStore.edit { preferences ->
            preferences.remove(usernameKey(credentialId))
            preferences.remove(passwordKey(credentialId))
            preferences.remove(legacyAllowInsecureTlsKey(credentialId))
        }
    }

    /**
     * Forces SharedPreferencesMigration to complete before file-based backup code copies the store.
     */
    suspend fun ensureMigrated() {
        dataStore.data.first()
    }

    private fun usernameKey(credentialId: String): Preferences.Key<String> =
        stringPreferencesKey(key(credentialId, KEY_USERNAME))

    private fun passwordKey(credentialId: String): Preferences.Key<String> =
        stringPreferencesKey(key(credentialId, KEY_PASSWORD))

    private fun legacyAllowInsecureTlsKey(credentialId: String): Preferences.Key<Boolean> =
        booleanPreferencesKey(key(credentialId, LEGACY_KEY_ALLOW_INSECURE_TLS))

    private fun key(credentialId: String, field: String): String = "$credentialId.$field"

    companion object {
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val LEGACY_KEY_ALLOW_INSECURE_TLS = "allowInsecureTls"

        fun createForTesting(dataStore: DataStore<Preferences>): WebDavCredentialStore =
            WebDavCredentialStore(dataStore)
    }
}
