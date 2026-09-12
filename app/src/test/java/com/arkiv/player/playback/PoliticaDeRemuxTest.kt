package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions around remuxing, pinned here because both mistakes are expensive: remuxing what
 * did not need it burns minutes and a gigabyte of the user's phone, and skipping what did sends
 * the TV a container it refuses.
 */
class PoliticaDeRemuxTest {

    @Test
    fun `only an MPEG-TS needs remuxing`() {
        assertTrue(PoliticaDeRemux.hayQueRemuxear("video/mp2t"))
    }

    /**
     * An mp4 is already what the receiver wants. Remuxing one would spend the whole cost to
     * produce the same file -- and Magis serves plenty of them (`MagisResolve` picks `_media.mp4`
     * whenever the portal's `videoFormat` is not `ts`).
     */
    @Test
    fun `what the receiver already accepts is left alone`() {
        assertFalse(PoliticaDeRemux.hayQueRemuxear("video/mp4"))
        assertFalse(PoliticaDeRemux.hayQueRemuxear("video/webm"))
    }

    /** Unknown means leave it alone: the segmenter still covers it, and a failed remux is worse. */
    @Test
    fun `an unknown container is not remuxed on a guess`() {
        assertFalse(PoliticaDeRemux.hayQueRemuxear(null))
        assertFalse(PoliticaDeRemux.hayQueRemuxear(""))
        assertFalse(PoliticaDeRemux.hayQueRemuxear("application/octet-stream"))
    }

    @Test
    fun `the same origin always maps to the same file`() {
        val a = PoliticaDeRemux.nombreDeArchivo("http://cdn/vod/ABC_media.ts")
        val b = PoliticaDeRemux.nombreDeArchivo("http://cdn/vod/ABC_media.ts")
        assertEquals(a, b)
        assertTrue(a.endsWith(".mp4"))
    }

    @Test
    fun `different origins do not collide`() {
        assertNotEquals(
            PoliticaDeRemux.nombreDeArchivo("http://cdn/vod/ABC_media.ts"),
            PoliticaDeRemux.nombreDeArchivo("http://cdn/vod/DEF_media.ts"),
        )
    }

    /**
     * The auth blob rides in the proxy url's query, and a filename is not a place for credentials:
     * it ends up in logs, in `ls`, and in any crash report that lists open files.
     */
    @Test
    fun `the file name never carries the origin verbatim`() {
        val nombre = PoliticaDeRemux.nombreDeArchivo("http://127.0.0.1:41234/s?h=Q29udGVudC1BdXRo&u=x")
        assertFalse(nombre.contains("Q29udGVudC1BdXRo"))
        assertFalse(nombre.contains("127.0.0.1"))
        assertTrue("expected <hex>.mp4, got $nombre", Regex("^[0-9a-f]+\\.mp4$").matches(nombre))
    }

    // --- when it is safe to hand the receiver a remux still being written ---

    /**
     * A remux that has only just begun is not castable: the receiver drains it in seconds and
     * stalls, which looks exactly like the bug this whole effort is fixing.
     */
    @Test
    fun `a remux that just started is not castable yet`() {
        // 1 MB of a 900 MB, two-hour title: a couple of seconds of content.
        assertFalse(PoliticaDeRemux.sePuedeEmpezar(1_000_000, 900_000_000, 7_200_000))
    }

    @Test
    fun `enough finished content is castable`() {
        // 10% of a two-hour title is 12 minutes: far past the floor.
        assertTrue(PoliticaDeRemux.sePuedeEmpezar(90_000_000, 900_000_000, 7_200_000))
    }

    /**
     * Resuming matters: 40 s of remux is plenty from the start and useless when the person is
     * resuming at minute 62, because everything before that point is content they are skipping.
     */
    @Test
    fun `resuming needs the remux to have reached that point`() {
        val total = 900_000_000L
        val durMs = 7_200_000L
        // 10% done = 12 minutes of content.
        assertTrue(PoliticaDeRemux.sePuedeEmpezar(90_000_000, total, durMs, posicionMs = 60_000))
        assertFalse(PoliticaDeRemux.sePuedeEmpezar(90_000_000, total, durMs, posicionMs = 3_720_000))
    }

    /** Nothing known yet is not an invitation to guess. */
    @Test
    fun `without a size or a duration the answer is no`() {
        assertFalse(PoliticaDeRemux.sePuedeEmpezar(0, 900_000_000, 7_200_000))
        assertFalse(PoliticaDeRemux.sePuedeEmpezar(90_000_000, 0, 7_200_000))
        assertFalse(PoliticaDeRemux.sePuedeEmpezar(90_000_000, 900_000_000, 0))
    }
}
