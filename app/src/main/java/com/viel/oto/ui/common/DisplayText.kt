package com.viel.oto.ui.common


fun formatPeopleSubtitle(
    author: String,
    narrator: String,
    fallback: String = "Unknown"
): String {
    return listOf(author, narrator)
        .filter { it.isNotBlank() }
        .joinToString(" - ")
        .ifBlank { fallback }
}