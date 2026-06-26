package com.viel.oto.shared.policy

import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Boundary and branch coverage for the shared time/size formatting helpers.
 *
 * The production helpers use [Locale.getDefault] (not Locale.ROOT as one might assume), so the
 * decimal separator and digit shaping depend on the runtime locale. To keep assertions stable and
 * repeatable these tests pin the default Locale and TimeZone for the duration of the run and
 * restore them afterwards.
 */
class TimeUtilsTest {

    private lateinit var originalLocale: Locale
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun pinLocaleAndZone() {
        originalLocale = Locale.getDefault()
        originalTimeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreLocaleAndZone() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalTimeZone)
    }

    // --- formatTime -----------------------------------------------------------------------------
    // NOTE: production always emits a fixed-width "%02d:%02d:%02d" (HH:MM:SS) shape and performs
    // NO negative coercion. This differs from the task description ("0:05", coerce to 0).

    @Test
    fun `formatTime renders zero as full width clock`() {
        assertEquals("00:00:00", formatTime(0L))
    }

    @Test
    fun `formatTime under one minute keeps hour and minute fields`() {
        // 5 seconds -> 00:00:05 (description expected "0:05")
        assertEquals("00:00:05", formatTime(5_000L))
    }

    @Test
    fun `formatTime under one hour zero pads minutes and seconds`() {
        // 5m09s -> 00:05:09 (description expected "5:09")
        assertEquals("00:05:09", formatTime(309_000L))
    }

    @Test
    fun `formatTime at or above one hour shows hours field`() {
        // 1h05m09s -> 01:05:09 (description expected "1:05:09"; hours are zero padded too)
        assertEquals("01:05:09", formatTime(3_909_000L))
    }

    @Test
    fun `formatTime exactly on the hour boundary`() {
        assertEquals("01:00:00", formatTime(3_600_000L))
    }

    @Test
    fun `formatTime carries seconds and minutes into the next unit`() {
        // 59:59 just below the hour, then +1s rolls to 01:00:00
        assertEquals("00:59:59", formatTime(3_599_000L))
        assertEquals("01:00:00", formatTime(3_600_000L))
    }

    @Test
    fun `formatTime does not coerce negative input`() {
        // -1s -> totalSeconds = -1 -> "%02d" of -1 is "-1"; no clamping happens in production.
        assertEquals("00:00:-1", formatTime(-1_000L))
    }

    // --- formatCompactDuration ------------------------------------------------------------------
    // NOTE: production has NO seconds component. It only renders "<h>h <m>m" or "<m>m".
    // This contradicts the task description ("0s", "45s", "5m 9s").

    @Test
    fun `formatCompactDuration zero renders zero minutes`() {
        assertEquals("0m", formatCompactDuration(0L))
    }

    @Test
    fun `formatCompactDuration sub minute truncates to zero minutes`() {
        // 45s -> 0m (no seconds support; description expected "45s")
        assertEquals("0m", formatCompactDuration(45_000L))
    }

    @Test
    fun `formatCompactDuration under one hour shows only minutes`() {
        // 5m09s -> 5m (seconds dropped; description expected "5m 9s")
        assertEquals("5m", formatCompactDuration(309_000L))
    }

    @Test
    fun `formatCompactDuration with hours shows hours and minutes only`() {
        // 1h05m -> 1h 5m
        assertEquals("1h 5m", formatCompactDuration(3_900_000L))
    }

    @Test
    fun `formatCompactDuration negative truncates toward zero`() {
        // integer division toward zero -> "0m"
        assertEquals("0m", formatCompactDuration(-1_000L))
    }

    // --- formatFileSize -------------------------------------------------------------------------

    @Test
    fun `formatFileSize non positive returns zero bytes`() {
        assertEquals("0 B", formatFileSize(0L))
        assertEquals("0 B", formatFileSize(-1L))
        assertEquals("0 B", formatFileSize(Long.MIN_VALUE))
    }

    @Test
    fun `formatFileSize one byte stays in byte unit`() {
        assertEquals("1.0 B", formatFileSize(1L))
    }

    @Test
    fun `formatFileSize below one kilobyte stays in byte unit`() {
        assertEquals("512.0 B", formatFileSize(512L))
    }

    @Test
    fun `formatFileSize kilobyte unit`() {
        assertEquals("1.0 KB", formatFileSize(1_024L))
    }

    @Test
    fun `formatFileSize megabyte unit`() {
        assertEquals("1.0 MB", formatFileSize(1_048_576L))
    }

    @Test
    fun `formatFileSize gigabyte unit`() {
        assertEquals("1.0 GB", formatFileSize(1_073_741_824L))
    }

    @Test
    fun `formatFileSize terabyte unit`() {
        assertEquals("1.0 TB", formatFileSize(1_099_511_627_776L))
    }

    @Test
    fun `formatFileSize beyond terabyte clamps to terabyte unit`() {
        // 1 PiB = 1024^5; digitGroups would be 5 but is coerced to lastIndex (4 = TB).
        // 1024^5 / 1024^4 = 1024.0 -> "1024.0 TB"
        val onePebibyte = 1_125_899_906_842_624L
        assertEquals("1024.0 TB", formatFileSize(onePebibyte))
    }

    // --- formatDate -----------------------------------------------------------------------------

    @Test
    fun `formatDate produces the expected shape`() {
        val formatted = formatDate(0L)
        assertTrue(
            "expected yyyy-MM-dd HH:mm shape but was '$formatted'",
            Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(formatted)
        )
    }

    @Test
    fun `formatDate respects the fixed UTC timezone`() {
        // Epoch 0 in UTC is 1970-01-01 00:00.
        assertEquals("1970-01-01 00:00", formatDate(0L))
    }
}
