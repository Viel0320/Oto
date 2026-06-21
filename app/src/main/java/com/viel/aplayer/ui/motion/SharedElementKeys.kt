package com.viel.aplayer.ui.motion

/**
 * Centralized Compose motion identity source.
 *
 * Defines the stable string keys consumed by SharedTransitionLayout across route and overlay boundaries.
 * Keeping the key builders in one object prevents Home, Detail, mini-player, and full-player components
 * from silently drifting into different transition channels.
 */
object SharedElementKeys {
    fun home2DetailCover(bookId: String): String = "home2detail_cover_$bookId"

    fun homeList2DetailCover(bookId: String): String = "home_list2detail_cover_$bookId"

    fun home2playerCover(bookId: String): String = "home2player_cover_$bookId"

    fun detail2playerCover(bookId: String): String = "detail2player_cover_$bookId"

    fun search2playerCover(bookId: String): String = "search2player_cover_$bookId"

    fun search2detailCover(bookId: String): String = "search2detail_cover_$bookId"

    fun mini2playerCover(): String = "mini2player_cover"

    fun playerBounds(): String = "player_Bounds"

}
