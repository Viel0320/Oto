package com.viel.aplayer.logger

import android.util.Log

/**
 * ABS 日志公共底座。
 *
 * 设计目标：
 * 1. 所有 ABS logger 共享同一套计时与脱敏规则，避免不同链路各自手写导致口径不一致。
 * 2. 在日志输出前统一抹掉 token、password、Bearer header、query 参数里的敏感值。
 * 3. 对超长 URL、sourcePath、itemId 做裁剪，避免 Logcat 被大字段淹没。
 */
internal object AbsLogClock {
    fun mark(): Long = System.nanoTime()

    fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000L
}

/**
 * ABS 敏感信息脱敏工具。
 *
 * 这里只做纯字符串处理，不依赖 Android 运行时，便于后续补 JVM 单测锁住“日志不泄漏凭据”。
 */
internal object AbsLogSanitizer {
    private val bearerRegex = Regex("Bearer\\s+\\S+", RegexOption.IGNORE_CASE)
    private val tokenJsonRegex = Regex("\"token\"\\s*:\\s*\"[^\"]*\"", RegexOption.IGNORE_CASE)
    private val passwordJsonRegex = Regex("\"password\"\\s*:\\s*\"[^\"]*\"", RegexOption.IGNORE_CASE)
    private val authorizationRegex = Regex("(Authorization\\s*[:=]\\s*Bearer\\s+)(\\S+)", RegexOption.IGNORE_CASE)
    private val tokenQueryRegex = Regex("((?:token|password)=)([^&#\\s]+)", RegexOption.IGNORE_CASE)

    fun sanitizeText(raw: String?): String {
        val value = raw.orEmpty()
        return value
            .replace(bearerRegex, "Bearer <redacted>")
            .replace(tokenJsonRegex, "\"token\":\"<redacted>\"")
            .replace(passwordJsonRegex, "\"password\":\"<redacted>\"")
            .replace(authorizationRegex, "$1<redacted>")
            .replace(tokenQueryRegex, "$1<redacted>")
    }

    /**
     * URL 日志默认去掉 query 和 fragment，再做统一脱敏与裁剪。
     * 这样既能保留定位接口所需的 path，又不会把 token 或临时签名打进日志。
     */
    fun sanitizeUrl(raw: String?): String =
        compact(
            sanitizeText(
                raw.orEmpty()
                    .substringBefore('#')
                    .substringBefore('?')
            )
        )

    fun compact(raw: String?, maxLength: Int = 160): String {
        val value = sanitizeText(raw).ifBlank { "<empty>" }
        return if (value.length <= maxLength) value else "${value.take(maxLength)}..."
    }

    fun shortId(raw: String?, maxLength: Int = 48): String = compact(raw, maxLength)
}

/**
 * ABS logger 的统一输出入口。
 *
 * 所有消息在真正写入 Logcat 前都会再次执行一次脱敏，保证调用方就算遗漏了某个字段的手动裁剪，
 * 也不会直接把敏感值原样输出。
 */
internal object AbsLogEmitter {
    fun debug(tag: String, message: String) {
        runCatching { Log.d(tag, AbsLogSanitizer.sanitizeText(message)) }
    }

    fun warn(tag: String, message: String) {
        runCatching { Log.w(tag, AbsLogSanitizer.sanitizeText(message)) }
    }
}
