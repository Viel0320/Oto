package com.viel.aplayer.abs.net

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.viel.aplayer.abs.net.dto.AbsAuthorizeResponseDto
import com.viel.aplayer.abs.net.dto.AbsBatchGetItemsResponseDto
import com.viel.aplayer.abs.net.dto.AbsLibrariesResponseDto
import com.viel.aplayer.abs.net.dto.AbsLibraryItemsResponseDto
import com.viel.aplayer.abs.net.dto.AbsPlaybackSessionDto
import com.viel.aplayer.abs.net.dto.AbsStatusDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsApiContractTest {

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun `status sample must expose server version`() {
        val dto = parse<AbsStatusDto>("abs/status_2_35_1.json")
        assertEquals("2.35.1", dto.serverVersion)
        assertTrue(dto.isInit == true)
    }

    @Test
    fun `authorize sample must parse user object`() {
        val dto = parse<AbsAuthorizeResponseDto>("abs/authorize_success.json")
        assertEquals("user-id", dto.user?.id)
        assertEquals("demo-user", dto.user?.username)
        assertEquals("redacted-token", dto.user?.token)
    }

    @Test
    fun `libraries sample must use wrapper object not bare array`() {
        val dto = parse<AbsLibrariesResponseDto>("abs/libraries.json")
        assertEquals(2, dto.libraries?.size)
        assertEquals("book", dto.libraries?.firstOrNull()?.mediaType)
        assertEquals("podcast", dto.libraries?.getOrNull(1)?.mediaType)
    }

    @Test
    fun `get authorize 404 sample must stay non json`() {
        val html = resourceText("abs/authorize_get_404.html")
        assertTrue(html.contains("Cannot GET /audiobookshelf/api/authorize"))
        assertFalse(html.trim().startsWith("{"))
    }

    @Test
    fun `minified sample must keep top level results and not require tracks`() {
        val dto = parse<AbsLibraryItemsResponseDto>("abs/library_items_minified.json")
        val first = dto.results?.firstOrNull()
        assertNotNull(first)
        assertEquals("item-1", first?.id)
        assertNull(first?.media?.tracks)
        assertNotNull(first?.media)
    }

    @Test
    fun `detail sample must expose track content url and audioFiles separately`() {
        val dto = parse<com.viel.aplayer.abs.net.dto.AbsLibraryItemDto>("abs/item_detail_expanded.json")
        val firstTrack = dto.media?.tracks?.firstOrNull()
        val firstAudioFile = dto.media?.audioFiles?.firstOrNull()
        assertEquals("/api/items/item-1/file/856465", firstTrack?.contentUrl)
        assertNotNull(firstAudioFile)
    }

    @Test
    fun `batch sample must use libraryItems wrapper`() {
        val dto = parse<AbsBatchGetItemsResponseDto>("abs/batch_get_items.json")
        assertEquals(1, dto.libraryItems?.size)
        assertEquals("item-1", dto.libraryItems?.firstOrNull()?.id)
    }

    @Test
    fun `play session sample must parse hls audio track`() {
        val dto = parse<AbsPlaybackSessionDto>("abs/play_session.json")
        val firstTrack = dto.audioTracks?.firstOrNull()
        assertEquals("session-1", dto.id)
        assertEquals("/hls/session-1/output.m3u8", firstTrack?.contentUrl)
    }

    @Test
    fun `progress 404 should map to null contract`() {
        val text = resourceText("abs/progress_404.txt").trim()
        assertEquals("Not Found", text)
        assertFalse(text.startsWith("{"))
    }

    @Test
    fun `sync and close success body should be plain OK not json`() {
        val text = resourceText("abs/text_ok.txt").trim()
        assertEquals("OK", text)
        assertFalse(text.startsWith("{"))
    }

    private inline fun <reified T> parse(path: String): T {
        val json = resourceText(path)
        return requireNotNull(moshi.adapter(T::class.java).fromJson(json)) {
            "Failed to parse sample: $path"
        }
    }

    private fun resourceText(path: String): String =
        javaClass.classLoader?.getResourceAsStream(path)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: error("Missing resource: $path")
}
