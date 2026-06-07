// Package Relocation: Move RelatedSection to the domain model package as it is a pure data structure representing a related sections domain model.
package com.viel.aplayer.domain.model

import com.viel.aplayer.data.entity.BookWithProgress

/**
 * Related books section model (Data container for grouping recommended audiobooks)
 * Represents collection rows such as same-author or same-narrator items.
 */
data class RelatedSection(
    /** Section title label (To display creator or narrator identity string) */
    val name: String,
    /** Section books list (To store related audiobook progress items) */
    val books: List<BookWithProgress>
)
