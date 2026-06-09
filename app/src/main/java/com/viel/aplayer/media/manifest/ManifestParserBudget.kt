package com.viel.aplayer.media.manifest

/**
 * Manifest Entry Budget (Caps user-controlled sidecar collections before import state growth)
 *
 * Keeps M3U8 items, CUE referenced files, and CUE chapter candidates bounded so oversized local sidecar files produce
 * deterministic partial imports instead of growing parser memory without limit.
 */
private const val MAX_MANIFEST_ITEMS = 10_000

/**
 * Manifest Text Budget (Caps user-controlled sidecar metadata strings before they enter import state)
 *
 * Bounds playlist titles, chapter titles, and manifest paths while preserving normal audiobook sidecar content.
 */
private const val MAX_MANIFEST_TEXT_CHARS = 8_192

/**
 * Manifest Text Limiter (Normalizes and bounds sidecar strings at parser boundaries)
 *
 * Trims whitespace and clips oversized titles or paths before those values are retained by import pipeline models.
 */
internal fun String.limitManifestText(): String =
    trim().take(MAX_MANIFEST_TEXT_CHARS)

/**
 * Manifest Entry Limiter (Stops accepting sidecar entries before parser state grows without bound)
 *
 * Returns false when the collection has already reached the manifest budget so callers can stop parsing further local
 * entries and keep partial imports deterministic.
 */
internal fun <T> MutableList<T>.addWithinManifestBudget(value: T): Boolean {
    if (size >= MAX_MANIFEST_ITEMS) return false
    add(value)
    return true
}
