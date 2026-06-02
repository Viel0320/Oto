package com.viel.aplayer.abs.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.viel.aplayer.logger.AbsAuthLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.absCredentialDataStore: DataStore<Preferences> by preferencesDataStore(name = "abs_credentials")

/**
 * ABS 凭据仓库只负责保存服务器连接所需的敏感字段。
 *
 * 设计约束：
 * 1. Room 与 UI 永远只拿 `credentialId`，不直接拿 token。
 * 2. 先用 DataStore 落盘，后续可替换成更强的加密存储而不影响调用方。
 */
class AbsCredentialStore private constructor(
    private val dataStore: DataStore<Preferences>,
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
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
        // 详尽中文注释：凭据真正落盘成功后，记录一条“认证域”日志，帮助排查“登录成功但后续找不到 credential”的问题。
        // 这里刻意只记录 credentialId、baseUrl、userId、username，绝不记录 token 本体。
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
        // 详尽中文注释：每次从凭据仓库读取都记“是否命中”，便于区分“root 写错 credentialId”和“凭据被删/损坏”两类问题。
        AbsAuthLogger.logCredentialGet(credentialId = credentialId, found = credential != null)
        return credential
    }

    suspend fun delete(credentialId: String?) {
        if (credentialId.isNullOrBlank()) return
        dataStore.edit { preferences ->
            preferences.remove(credentialKey(credentialId))
        }
        // 详尽中文注释：删除凭据是 ABS 生命周期里的高风险动作，必须有单独日志，方便追查“为什么某个 server 突然鉴权失效”。
        AbsAuthLogger.logCredentialDelete(credentialId)
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

        // 提供给 JVM 单元测试的构造入口，避免测试为了凭据读写引入 Android Context。
        internal fun createForTesting(dataStore: DataStore<Preferences>): AbsCredentialStore =
            AbsCredentialStore(dataStore = dataStore)

        fun getInstance(context: Context): AbsCredentialStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AbsCredentialStore(context.applicationContext.absCredentialDataStore).also { INSTANCE = it }
            }
        }

        // 仅供测试重置全局单例，避免不同测试之间复用旧的 DataStore 实例。
        internal fun resetForTesting() {
            INSTANCE = null
        }
    }
}
