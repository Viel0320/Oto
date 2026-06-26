package com.viel.oto.library.vfs.sourceProvider.remote

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Describes the concrete byte window that a remote HTTP Range request should ask for.
 *
 * `expectedBodyLength` is derived from the emitted start/end window rather than the caller's
 * original length so overflow-saturated ranges and known-size EOF clamps enforce the real response
 * size that the server is allowed to return.
 */
data class RemoteRangePlan(
    val start: Long,
    val end: Long,
    val expectedBodyLength: Int
) {
    val headerValue: String = "bytes=$start-$end"
}

/**
 * Stores the byte interval advertised by a standard HTTP `Content-Range` header.
 *
 * The total-size suffix is intentionally ignored because source providers only need to validate the
 * returned body window against the request they just emitted.
 */
data class RemoteContentRange(
    val start: Long,
    val end: Long
)

/**
 * Selects how strictly a provider should compare a partial response end offset.
 *
 * WebDAV can often clamp against a known file size and require the exact end, while ABS tracks may
 * have unknown local sizes and must accept a shorter EOF response that stays inside the requested
 * window.
 */
enum class RemoteRangeEndPolicy {
    ExactEnd,
    WithinRequestedEnd
}

/**
 * Represents a bounded remote range body read.
 *
 * `TooLarge` is returned after probing exactly one byte beyond the expected body window, which lets
 * callers reject oversized responses without buffering the rest of a media file.
 */
sealed interface RemoteRangeBodyReadResult {
    data class Success(val bytes: ByteArray) : RemoteRangeBodyReadResult
    data class TooLarge(
        val requestedLength: Int,
        val observedBytes: Long
    ) : RemoteRangeBodyReadResult
}

/**
 * Receives diagnostics produced while the range strategy enforces HTTP byte-window rules.
 *
 * Providers adapt this narrow sink to their own logging families, which keeps strategy diagnostics
 * emitted at the point of decision without coupling shared range rules to ABS or WebDAV loggers.
 */
interface RemoteRangeStrategyLogSink {
    fun onContentRangeMismatch(
        plan: RemoteRangePlan,
        contentRange: RemoteContentRange?,
        endPolicy: RemoteRangeEndPolicy
    )

    fun onBodyTooLarge(result: RemoteRangeBodyReadResult.TooLarge)
}

/**
 * Centralizes HTTP Range invariants shared by remote VFS source providers.
 *
 * This module deliberately excludes URL construction, authentication, request execution, logging,
 * and provider-specific exception mapping so ABS and WebDAV keep ownership of their protocol
 * adapters while reusing the same byte-window safety rules.
 */
object RemoteHttpRangeReadStrategy {
    /**
     * Builds the emitted Range request window for a bounded read.
     *
     * `knownFileSize` is optional because some remote catalogs do not expose stable track sizes. When
     * it is present, the plan is clamped to EOF; when absent, only arithmetic overflow is saturated.
     */
    fun plan(offset: Long, length: Int, knownFileSize: Long? = null): RemoteRangePlan? {
        if (offset < 0L || length <= 0) return null
        val normalizedSize = knownFileSize?.takeIf { it > 0L }
        if (normalizedSize != null && offset >= normalizedSize) return null
        val rawEnd = offset.saturatingAdd(length.toLong() - 1L)
        val end = normalizedSize?.let { minOf(rawEnd, it - 1L) } ?: rawEnd
        val expectedBodyLength = ((end - offset) + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return RemoteRangePlan(
            start = offset,
            end = end,
            expectedBodyLength = expectedBodyLength
        )
    }

    /**
     * Parses the byte interval from a `Content-Range` header.
     *
     * Invalid, reversed, or missing ranges return null so providers can convert the mismatch into
     * their own typed protocol failure.
     */
    fun parseContentRange(value: String?): RemoteContentRange? {
        val match = value?.trim()?.let(CONTENT_RANGE_PATTERN::matchEntire) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        if (start > end) return null
        return RemoteContentRange(start = start, end = end)
    }

    /**
     * Validates that a partial response describes the byte window permitted by a range plan.
     *
     * The start must always match. The end either matches exactly or remains within the requested
     * window depending on the provider's local knowledge about the remote file size.
     */
    fun validateContentRange(
        contentRange: RemoteContentRange?,
        plan: RemoteRangePlan,
        endPolicy: RemoteRangeEndPolicy,
        logSink: RemoteRangeStrategyLogSink? = null
    ): Boolean {
        val isValid = if (contentRange == null || contentRange.start != plan.start) {
            false
        } else when (endPolicy) {
            RemoteRangeEndPolicy.ExactEnd -> contentRange.end == plan.end
            RemoteRangeEndPolicy.WithinRequestedEnd -> contentRange.end <= plan.end
        }
        if (!isValid) {
            logSink?.onContentRangeMismatch(
                plan = plan,
                contentRange = contentRange,
                endPolicy = endPolicy
            )
        }
        return isValid
    }

    /**
     * Validates only the start offset for open-ended Range streams.
     *
     * Offset-based streaming requests use `Range: bytes=start-`, so callers cannot know the response
     * end ahead of time and should only reject responses that begin at a different offset.
     */
    fun validateContentRangeStart(contentRange: RemoteContentRange?, start: Long): Boolean =
        contentRange?.start == start

    /**
     * Reads the expected body window and probes one extra byte for oversized remote responses.
     *
     * Returning a result instead of throwing keeps provider-specific error classes and log messages
     * out of this shared protocol module.
     */
    fun readBodyWithLimit(
        stream: InputStream,
        expectedLength: Int,
        logSink: RemoteRangeStrategyLogSink? = null
    ): RemoteRangeBodyReadResult {
        if (expectedLength <= 0) {
            return RemoteRangeBodyReadResult.Success(ByteArray(0))
        }
        val output = ByteArrayOutputStream(expectedLength.coerceAtMost(DEFAULT_RANGE_BUFFER_SIZE))
        val buffer = ByteArray(DEFAULT_RANGE_BUFFER_SIZE)
        var remaining = expectedLength
        while (remaining > 0) {
            val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) {
                return RemoteRangeBodyReadResult.Success(output.toByteArray())
            }
            output.write(buffer, 0, read)
            remaining -= read
        }
        if (stream.read() != -1) {
            val result = RemoteRangeBodyReadResult.TooLarge(
                requestedLength = expectedLength,
                observedBytes = expectedLength.toLong() + 1L
            )
            logSink?.onBodyTooLarge(result)
            return result
        }
        return RemoteRangeBodyReadResult.Success(output.toByteArray())
    }

    private fun Long.saturatingAdd(value: Long): Long =
        if (this > Long.MAX_VALUE - value) Long.MAX_VALUE else this + value

    private const val DEFAULT_RANGE_BUFFER_SIZE = 16 * 1024
    private val CONTENT_RANGE_PATTERN = Regex("""^bytes\s+(\d+)-(\d+)/(?:\d+|\*)$""")
}
