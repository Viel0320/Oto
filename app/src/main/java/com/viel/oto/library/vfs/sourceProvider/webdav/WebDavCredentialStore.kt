package com.viel.oto.library.vfs.sourceProvider.webdav

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import java.util.UUID

data class WebDavCredential(
    val id: String,
    val username: String,
    val password: String
)

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
        preferences.edit {
            putString(key(credentialId, KEY_USERNAME), username)
                .putString(key(credentialId, KEY_PASSWORD), encodedPassword)
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
        preferences.edit {
            remove(key(credentialId, KEY_USERNAME))
                .remove(key(credentialId, KEY_PASSWORD))
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
