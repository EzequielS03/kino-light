package com.arkiv.player.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastRequestBuilderTest {

    @Test
    fun `archive prefiere castUrl sobre mediaUrl`() {
        val r = CastRequestBuilder.build(
            episodeId = "ep1", title = "Doc", subtitle = "", artworkUrl = "https://p.jpg",
            mediaUrl = "https://archive.org/x.mkv", castUrl = "https://archive.org/x.mp4",
            lanUrl = null, lanMime = null, startPositionMs = 5000,
        )!!
        assertEquals("https://archive.org/x.mp4", r.uri)
        assertEquals("video/mp4", r.mimeType)
        assertEquals("Doc", r.title)
        assertEquals(5000, r.startPositionMs)
    }

    @Test
    fun `archive sin castUrl usa mediaUrl`() {
        val r = CastRequestBuilder.build(
            episodeId = "ep1", title = "t", subtitle = "s", artworkUrl = "",
            mediaUrl = "https://archive.org/x.mkv", castUrl = null,
            lanUrl = null, lanMime = null, startPositionMs = 0,
        )!!
        assertEquals("https://archive.org/x.mkv", r.uri)
        assertEquals("video/x-matroska", r.mimeType)
    }

    @Test
    fun `sin ninguna url no se puede castear`() {
        assertNull(
            CastRequestBuilder.build(
                episodeId = "ep1", title = "t", subtitle = "s", artworkUrl = "",
                mediaUrl = "", castUrl = null,
                lanUrl = null, lanMime = null, startPositionMs = 0,
            ),
        )
    }

    @Test
    fun `una posicion negativa se lleva a cero`() {
        val r = CastRequestBuilder.build(
            episodeId = "ep1", title = "t", subtitle = "s", artworkUrl = "",
            mediaUrl = "https://a/x.mp4", castUrl = null,
            lanUrl = null, lanMime = null, startPositionMs = -500,
        )!!
        assertEquals(0, r.startPositionMs)
    }

    @Test
    fun `castUrl en blanco pero no null se ignora`() {
        val r = CastRequestBuilder.build(
            episodeId = "ep1", title = "t", subtitle = "s", artworkUrl = "",
            mediaUrl = "https://archive.org/x.webm", castUrl = "",
            lanUrl = null, lanMime = null, startPositionMs = 0,
        )!!
        assertEquals("https://archive.org/x.webm", r.uri)
        assertEquals("video/webm", r.mimeType)
    }

    /**
     * La tabla de MIME por URL cubría cuatro extensiones (mp4/m4v/mkv/webm) y mandaba TODO lo demás
     * a `video/mp4`. Un `.ts` de magis o un `.avi` de archive se le anunciaban al receptor como
     * mp4, que es justo el string con el que decide si abre el stream. Ver `ContenedorDeVideo`.
     */
    @Test
    fun `el mime por URL cubre los contenedores que servimos, no solo cuatro`() {
        fun mimeDe(url: String) = CastRequestBuilder.build(
            episodeId = "ep1", title = "t", subtitle = "s", artworkUrl = "",
            mediaUrl = url, castUrl = null,
            lanUrl = null, lanMime = null, startPositionMs = 0,
        )!!.mimeType

        assertEquals("video/mp2t", mimeDe("https://cdn/vod/ABC_media.ts"))
        assertEquals("video/x-msvideo", mimeDe("https://archive.org/peli.avi"))
        assertEquals("video/mpeg", mimeDe("https://archive.org/peli.mpg"))
        // Y lo que ya andaba sigue andando.
        assertEquals("video/mp4", mimeDe("https://archive.org/peli.mp4"))
        assertEquals("video/x-matroska", mimeDe("https://archive.org/peli.mkv"))
    }

    @Test
    fun `vivo usa la url de la LAN del proxy y el mime de HLS`() {
        val r = CastRequestBuilder.build(
            episodeId = "live:espn", title = "ESPN", subtitle = "",
            artworkUrl = "", mediaUrl = "http://127.0.0.1:1/live.m3u8", castUrl = null,
            lanUrl = "http://192.168.3.20:1/live.m3u8",
            lanMime = null, startPositionMs = 45_000, isLive = true,
        )!!
        assertEquals("http://192.168.3.20:1/live.m3u8", r.uri)
        assertEquals("application/vnd.apple.mpegurl", r.mimeType)
    }

    @Test
    fun `vivo fuerza startPositionMs a cero aunque se pida otra cosa`() {
        val r = CastRequestBuilder.build(
            episodeId = "live:espn", title = "ESPN", subtitle = "",
            artworkUrl = "", mediaUrl = "http://127.0.0.1:1/live.m3u8", castUrl = null,
            lanUrl = "http://192.168.3.20:1/live.m3u8",
            lanMime = null, startPositionMs = 999_999, isLive = true,
        )!!
        assertEquals(0, r.startPositionMs)
    }

    @Test
    fun `vivo sin url de LAN no se puede castear`() {
        assertNull(
            CastRequestBuilder.build(
                episodeId = "live:espn", title = "ESPN", subtitle = "", artworkUrl = "",
                mediaUrl = "http://127.0.0.1:1/live.m3u8", castUrl = null,
                lanUrl = null, lanMime = null,
                startPositionMs = 0, isLive = true,
            ),
        )
    }

    @Test
    fun `vivo ignora castUrl y mediaUrl aunque vengan seteados`() {
        val r = CastRequestBuilder.build(
            episodeId = "live:espn", title = "ESPN", subtitle = "", artworkUrl = "",
            mediaUrl = "http://127.0.0.1:1/live.m3u8", castUrl = "https://no-deberia-usarse.mp4",
            lanUrl = "http://192.168.3.20:1/live.m3u8",
            lanMime = null, startPositionMs = 0, isLive = true,
        )!!
        assertEquals("http://192.168.3.20:1/live.m3u8", r.uri)
    }

    @Test
    fun `episodeId subtitle artworkUrl pasan sin cambios`() {
        val r = CastRequestBuilder.build(
            episodeId = "episode-42-custom", title = "Title", subtitle = "Season 2 Episode 5",
            artworkUrl = "https://example.com/poster.jpg",
            mediaUrl = "https://a/x.mp4", castUrl = null,
            lanUrl = null, lanMime = null, startPositionMs = 0,
        )!!
        assertEquals("episode-42-custom", r.episodeId)
        assertEquals("Season 2 Episode 5", r.subtitle)
        assertEquals("https://example.com/poster.jpg", r.artworkUrl)
    }
}
