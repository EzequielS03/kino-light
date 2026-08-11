package com.arkiv.player.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastRequestBuilderTest {

    @Test
    fun `torrent usa la url de la LAN y el mime real del stream`() {
        val r = CastRequestBuilder.build(
            episodeId = "torrent:abc::0", title = "Avatar", subtitle = "T1E1",
            artworkUrl = "https://p.jpg", mediaUrl = "http://127.0.0.1:1/video", castUrl = null,
            isTorrent = true, lanUrl = "http://192.168.3.20:34045/video",
            lanMime = "video/x-matroska", startPositionMs = 3000,
        )!!
        assertEquals("http://192.168.3.20:34045/video", r.uri)
        assertEquals("video/x-matroska", r.mimeType)
        assertEquals(3000, r.startPositionMs)
    }

    @Test
    fun `torrent sin url de LAN no se puede castear`() {
        assertNull(
            CastRequestBuilder.build(
                episodeId = "torrent:abc::0", title = "t", subtitle = "s", artworkUrl = "",
                mediaUrl = "http://127.0.0.1:1/video", castUrl = null,
                isTorrent = true, lanUrl = null, lanMime = "video/mp4", startPositionMs = 0,
            ),
        )
    }

    @Test
    fun `torrent sin mime conocido cae a mp4`() {
        val r = CastRequestBuilder.build(
            episodeId = "torrent:abc::0", title = "t", subtitle = "s", artworkUrl = "",
            mediaUrl = "http://127.0.0.1:1/video", castUrl = null,
            isTorrent = true, lanUrl = "http://192.168.3.20:1/video", lanMime = null,
            startPositionMs = 0,
        )!!
        assertEquals("video/mp4", r.mimeType)
    }

    @Test
    fun `archive prefiere castUrl sobre mediaUrl`() {
        val r = CastRequestBuilder.build(
            episodeId = "ep1", title = "Doc", subtitle = "", artworkUrl = "https://p.jpg",
            mediaUrl = "https://archive.org/x.mkv", castUrl = "https://archive.org/x.mp4",
            isTorrent = false, lanUrl = null, lanMime = null, startPositionMs = 5000,
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
            isTorrent = false, lanUrl = null, lanMime = null, startPositionMs = 0,
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
                isTorrent = false, lanUrl = null, lanMime = null, startPositionMs = 0,
            ),
        )
    }

    @Test
    fun `una posicion negativa se lleva a cero`() {
        val r = CastRequestBuilder.build(
            episodeId = "ep1", title = "t", subtitle = "s", artworkUrl = "",
            mediaUrl = "https://a/x.mp4", castUrl = null,
            isTorrent = false, lanUrl = null, lanMime = null, startPositionMs = -500,
        )!!
        assertEquals(0, r.startPositionMs)
    }

    @Test
    fun `torrent ignora castUrl aunque sea non-null`() {
        val r = CastRequestBuilder.build(
            episodeId = "torrent:abc::0", title = "t", subtitle = "s", artworkUrl = "",
            mediaUrl = "http://127.0.0.1:1/video", castUrl = "https://archive.org/x.mp4",
            isTorrent = true, lanUrl = "http://192.168.3.20:1/video", lanMime = "video/webm",
            startPositionMs = 0,
        )!!
        assertEquals("http://192.168.3.20:1/video", r.uri)
        assertEquals("video/webm", r.mimeType)
    }

    @Test
    fun `castUrl en blanco pero no null se ignora`() {
        val r = CastRequestBuilder.build(
            episodeId = "ep1", title = "t", subtitle = "s", artworkUrl = "",
            mediaUrl = "https://archive.org/x.webm", castUrl = "",
            isTorrent = false, lanUrl = null, lanMime = null, startPositionMs = 0,
        )!!
        assertEquals("https://archive.org/x.webm", r.uri)
        assertEquals("video/webm", r.mimeType)
    }

    @Test
    fun `vivo usa la url de la LAN del proxy y el mime de HLS`() {
        val r = CastRequestBuilder.build(
            episodeId = "live:espn", title = "ESPN", subtitle = "",
            artworkUrl = "", mediaUrl = "http://127.0.0.1:1/live.m3u8", castUrl = null,
            isTorrent = false, lanUrl = "http://192.168.3.20:1/live.m3u8",
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
            isTorrent = false, lanUrl = "http://192.168.3.20:1/live.m3u8",
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
                isTorrent = false, lanUrl = null, lanMime = null,
                startPositionMs = 0, isLive = true,
            ),
        )
    }

    @Test
    fun `vivo ignora castUrl y mediaUrl aunque vengan seteados`() {
        val r = CastRequestBuilder.build(
            episodeId = "live:espn", title = "ESPN", subtitle = "", artworkUrl = "",
            mediaUrl = "http://127.0.0.1:1/live.m3u8", castUrl = "https://no-deberia-usarse.mp4",
            isTorrent = false, lanUrl = "http://192.168.3.20:1/live.m3u8",
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
            isTorrent = false, lanUrl = null, lanMime = null, startPositionMs = 0,
        )!!
        assertEquals("episode-42-custom", r.episodeId)
        assertEquals("Season 2 Episode 5", r.subtitle)
        assertEquals("https://example.com/poster.jpg", r.artworkUrl)
    }
}
