package com.viel.aplayer.abs.auth

/**
 * ABS 凭据只在反腐层内部流转。
 *
 * 注意：
 * 1. `token` 不参与 `toString()` 输出，避免日志或异常里泄漏敏感信息。
 * 2. `baseUrl` 必须是已经规范化的服务器基地址，后续 API client 和 provider 都直接复用它。
 */
data class AbsCredential(
    val id: String,
    val baseUrl: String,
    val token: String,
    val userId: String? = null,
    val username: String? = null,
    val serverKey: String? = null
) {
    override fun toString(): String =
        "AbsCredential(id=$id, baseUrl=$baseUrl, userId=$userId, username=$username, serverKey=$serverKey, token=<redacted>)"
}
