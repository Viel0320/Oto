package com.viel.oto.media.parser

import java.util.Locale

internal object MetadataDescriptionRules {
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
        value
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\\r", "\n")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()

    fun normalizedFieldKey(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "")

    fun isDescriptionFieldName(value: String?): Boolean =
        normalizedFieldKey(value.orEmpty()) in preferredDescriptionKeys

    fun firstDescriptionFromCustomAndFallback(
        values: Map<String, String>,
        customPrefix: String,
        fallbackKeys: List<String>
    ): String =
        firstDescriptionFromOrderedKeys(
            values = values,
            keys = preferredDescriptionKeys.map { key -> "$customPrefix:$key" } + fallbackKeys
        )

    fun firstDescriptionFromFields(values: Map<String, String>): String {
        return preferredDescriptionKeys.firstNotNullOfOrNull { preferredKey ->
            values.entries.firstOrNull { (key, value) ->
                normalizedFieldKey(key) == preferredKey && value.isNotBlank()
            }?.value?.let(::normalizeDescriptionText)?.takeIf { it.isNotBlank() }
        }.orEmpty()
    }

    private fun firstDescriptionFromOrderedKeys(values: Map<String, String>, keys: List<String>): String =
        keys.firstNotNullOfOrNull { key ->
            values[key]
                ?.let(::normalizeDescriptionText)
                ?.takeIf { it.isNotBlank() }
        }.orEmpty()
}
