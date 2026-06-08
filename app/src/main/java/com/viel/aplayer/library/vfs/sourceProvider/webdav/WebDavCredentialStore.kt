package com.viel.aplayer.library.vfs.sourceProvider.webdav

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import java.util.UUID

// WebDAV credentials are persisted in separate SharedPreferences initially; LibraryRootEntity only references credentialId, allowing future Keystore migration.
data class WebDavCredential(
    val id: String,
    val username: String,
    val password: String
)

// This store encapsulates connection credentials, preventing usernames/passwords from scattering into Provider, UI, or Room tables.
class WebDavCredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(
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
        // Groups preference fields by credentialId, enabling concurrent storage of multiple WebDAV connections.
        preferences.edit {
            putString(key(credentialId, KEY_USERNAME), username)
                .putString(key(credentialId, KEY_PASSWORD), encodedPassword)
                // Legacy Per-Root TLS Cleanup (Remove obsolete local TLS bypass state)
                // Unsafe TLS is now controlled only by the global AppSettings switch, so persisted root-level flags must not influence future requests.
                .remove(key(credentialId, LEGACY_KEY_ALLOW_INSECURE_TLS))
            }
        return credential
    }

    fun get(credentialId: String?): WebDavCredential? {
        if (credentialId.isNullOrBlank()) return null
        val username = preferences.getString(key(credentialId, KEY_USERNAME), null) ?: return null
        val encodedPassword = preferences.getString(key(credentialId, KEY_PASSWORD), null) ?: return null
        val password = runCatching {
            String(Base64.decode(encodedPassword, Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrDefault(encodedPassword)
        return WebDavCredential(
            id = credentialId,
            username = username,
            password = password
        )
    }

    fun delete(credentialId: String?) {
        if (credentialId.isNullOrBlank()) return
        // Erases credentials when deleting WebDAV root to avoid leaving stale username/password records in SharedPreferences.
        preferences.edit {
            remove(key(credentialId, KEY_USERNAME))
                .remove(key(credentialId, KEY_PASSWORD))
                // Legacy Per-Root TLS Cleanup (Delete obsolete TLS bypass marker with the credential)
                // Keeps removed roots from leaving misleading compatibility flags behind in SharedPreferences.
                .remove(key(credentialId, LEGACY_KEY_ALLOW_INSECURE_TLS))
        }
    }

    private fun key(credentialId: String, field: String): String = "$credentialId.$field"

    private companion object {
        private const val PREFS_NAME = "webdav_credentials"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val LEGACY_KEY_ALLOW_INSECURE_TLS = "allowInsecureTls"
    }
}
