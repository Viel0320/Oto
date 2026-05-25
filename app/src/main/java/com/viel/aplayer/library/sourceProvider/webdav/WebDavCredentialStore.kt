package com.viel.aplayer.library.source

import android.content.Context
import android.util.Base64
import java.util.UUID

// 为每一次改动添加详尽的中文注释：WebDAV 凭据先用独立 SharedPreferences 持久化，LibraryRootEntity 只保存 credentialId，后续可无侵入替换为 Keystore/加密存储。
data class WebDavCredential(
    val id: String,
    val username: String,
    val password: String,
    val allowInsecureTls: Boolean = false
)

// 为每一次改动添加详尽的中文注释：该仓库是远程连接凭据边界，避免 Provider、UI 和数据库表直接散落保存账号密码字段。
class WebDavCredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(
        username: String,
        password: String,
        allowInsecureTls: Boolean = false,
        credentialId: String = UUID.randomUUID().toString()
    ): WebDavCredential {
        val encodedPassword = Base64.encodeToString(password.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val credential = WebDavCredential(
            id = credentialId,
            username = username,
            password = password,
            allowInsecureTls = allowInsecureTls
        )
        // 为每一次改动添加详尽的中文注释：所有字段按 credentialId 分组写入，确保同一应用内可以同时保存多个 WebDAV 连接。
        preferences.edit()
            .putString(key(credentialId, KEY_USERNAME), username)
            .putString(key(credentialId, KEY_PASSWORD), encodedPassword)
            .putBoolean(key(credentialId, KEY_ALLOW_INSECURE_TLS), allowInsecureTls)
            .apply()
        return credential
    }

    fun get(credentialId: String?): WebDavCredential? {
        if (credentialId.isNullOrBlank()) return null
        val username = preferences.getString(key(credentialId, KEY_USERNAME), null) ?: return null
        val encodedPassword = preferences.getString(key(credentialId, KEY_PASSWORD), null) ?: return null
        val password = runCatching {
            String(Base64.decode(encodedPassword, Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrDefault(encodedPassword)
        val allowInsecureTls = preferences.getBoolean(key(credentialId, KEY_ALLOW_INSECURE_TLS), false)
        return WebDavCredential(
            id = credentialId,
            username = username,
            password = password,
            allowInsecureTls = allowInsecureTls
        )
    }

    fun delete(credentialId: String?) {
        if (credentialId.isNullOrBlank()) return
        // 为每一次改动添加详尽的中文注释：删除 WebDAV root 时同步清掉凭据引用，避免遗留不可见账号密码。
        preferences.edit()
            .remove(key(credentialId, KEY_USERNAME))
            .remove(key(credentialId, KEY_PASSWORD))
            .remove(key(credentialId, KEY_ALLOW_INSECURE_TLS))
            .apply()
    }

    private fun key(credentialId: String, field: String): String = "$credentialId.$field"

    private companion object {
        private const val PREFS_NAME = "webdav_credentials"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_ALLOW_INSECURE_TLS = "allowInsecureTls"
    }
}
