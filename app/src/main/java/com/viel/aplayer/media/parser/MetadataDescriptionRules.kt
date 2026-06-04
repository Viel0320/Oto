package com.viel.aplayer.media.parser

import java.util.Locale

// Description selection and line break sanitization belong to import semantic rules, not low-level range reading tools.
// Isolated here to prevent duplication across MP3/MP4/FLAC/Opus parsers and to keep RangeAudioParserSupport business-free.
internal object MetadataDescriptionRules {
    // Field names ordered by "user-maintained description fields first, generic comment fields as fallback".
    // Different tagging tools use variations like Description, Long Description, Summary, Comment; normalized before comparison.
    private val preferredDescriptionKeys = listOf(
        "comment",
        "comments",
        "description",
        "desc",
        "longdescription",
        "synopsis",
        "summary"
    )

    fun normalizeDescriptionText(value: String): String =
        // Normalizes both real line breaks (CRLF/CR) and literal "\n" / "\r\n" strings written by tag editors.
        // The UI handles rendering and HTML parsing; tag parsers should not guess raw line ending formats.
        value
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\\r", "\n")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()

    fun normalizedFieldKey(value: String): String =
        // Custom metadata fields vary widely (e.g., Long Description, LONG_DESCRIPTION, desc).
        // Normalized to lowercase alphanumeric strings to avoid mismatches from case or separator differences.
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "")

    fun isDescriptionFieldName(value: String?): Boolean =
        // ID3 TXXX and custom fields require name validation before using their values as the description.
        normalizedFieldKey(value.orEmpty()) in preferredDescriptionKeys

    fun firstDescriptionFromCustomAndFallback(
        values: Map<String, String>,
        customPrefix: String,
        fallbackKeys: List<String>
    ): String =
        // MP4 freeform fields are pre-saved as "$prefix:fieldName".
        // Scans custom fields before standard formats to centralize priority logic instead of delegating to callers.
        firstDescriptionFromOrderedKeys(
            values = values,
            keys = preferredDescriptionKeys.map { key -> "$customPrefix:$key" } + fallbackKeys
        )

    fun firstDescriptionFromFields(values: Map<String, String>): String {
        // Vorbis/FLAC/Opus comment maps are open-ended collections of tag pairs.
        // ID3 TXXX custom text frames reuse this priority order, only falling back to generic COMMENT/COMMENTS last.
        return preferredDescriptionKeys.firstNotNullOfOrNull { preferredKey ->
            values.entries.firstOrNull { (key, value) ->
                normalizedFieldKey(key) == preferredKey && value.isNotBlank()
            }?.value?.let(::normalizeDescriptionText)?.takeIf { it.isNotBlank() }
        }.orEmpty()
    }

    private fun firstDescriptionFromOrderedKeys(values: Map<String, String>, keys: List<String>): String =
        // Standard MP4 atom names require exact matches (e.g., ©des / ©cmt cannot be alphanumeric-compressed).
        keys.firstNotNullOfOrNull { key ->
            values[key]
                ?.let(::normalizeDescriptionText)
                ?.takeIf { it.isNotBlank() }
        }.orEmpty()
}
