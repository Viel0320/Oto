package com.viel.aplayer.ui.motion

/**
 * Shared Element Keys Registry (Centralized Compose motion identity source)
 *
 * Defines the stable string keys consumed by SharedTransitionLayout across route and overlay boundaries.
 * Keeping the key builders in one object prevents Home, Detail, mini-player, and full-player components
 * from silently drifting into different transition channels.
 */
object SharedElementKeys {
    /*
     * Home To Detail Cover Key (Book-scoped artwork transition channel)
     *
     * Builds the shared-element key used by the Home recent-card cover and the matching Detail cover.
     * The Home/Detail prefix keeps this route transition separate from the mini-player artwork channel
     * even when both transitions reference the same book ID.
     */
    fun home2DetailCover(bookId: String): String = "home2detail_cover_$bookId"

    /*
     * Home List To Detail Cover Key (List-scoped artwork transition channel)
     *
     * Builds the shared-element key used only by Home main-list cover thumbnails and Detail
     * covers opened from that list, keeping them isolated from the Home recent-card channel.
     */
    fun homeList2DetailCover(bookId: String): String = "home_list2detail_cover_$bookId"

    fun home2playerCover(bookId: String): String = "home2player_cover_$bookId"

    fun detail2playerCover(bookId: String): String = "detail2player_cover_$bookId"

    fun search2playerCover(bookId: String): String = "search2player_cover_$bookId"

    fun search2detailCover(bookId: String): String = "search2detail_cover_$bookId"

    /*
     * Mini To Player Cover Key (Book-scoped playback artwork transition channel)
     *
     * Builds the shared-element key used by CompactMediaPlayer, PillCompactMediaPlayer, and MainCoverView.
     * The book ID keeps cover morphing tied to the currently playing audiobook instead of reusing stale
     * artwork when playback switches between books.
     */
    fun mini2playerCover(bookId: String): String = "mini2player_cover_$bookId"

    /*
     * Player Bounds Key (Single playback surface transition channel)
     *
     * Returns the fixed shared-bounds key used by compact, pill, and full-player surfaces.
     * The bounds transition represents the playback container itself, so it intentionally stays
     * independent of book identity while cover artwork remains book-scoped.
     */
    fun playerBounds(): String = "player_Bounds"

}
