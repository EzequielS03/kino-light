package com.arkiv.player.data.magis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagisRefTest {

    /** A REAL gateway ref (`base64url(json).hmac`), with an already-expired `exp` and signed with
     *  a key the app doesn't have: it's exactly what's saved in today's databases. */
    private val refViejo =
        "eyJzIjoibWFnaXMiLCJwIjp7ImNvbnRlbnRfaWQiOiIxNDcwOTc0IiwicHJvZ3JhbV90eXBlIjoidGVsZXBsYXki" +
            "LCJlcGlzb2RlIjozfSwiZXhwIjoxNzU3MDAwMDAwfQ.lkewA6xe7e098nDA5WYNWA"

    @Test
    fun `round trip of its own format`() {
        val ref = MagisRef(contentId = "1470974", programType = "teleplay", episode = 3)

        assertEquals(ref, MagisRef.decode(ref.encode()))
    }

    @Test
    fun `a contentId with a colon inside survives`() {
        val ref = MagisRef(contentId = "cyx:raro:99", programType = "movie", episode = 0)

        assertEquals(ref, MagisRef.decode(ref.encode()))
    }

    @Test
    fun `an old gateway ref is understood even if expired and signed with another key`() {
        val ref = MagisRef.decode(refViejo)!!

        assertEquals("1470974", ref.contentId)
        assertEquals("teleplay", ref.programType)
        assertEquals(3, ref.episode)
        assertTrue(ref.isSeries)
    }

    @Test
    fun `an old ref from another source isn't Magis's`() {
        val deWeb = "eyJzIjoid2ViIiwicCI6eyJ1cmwiOiJ4In0sImV4cCI6MTc1NzAwMDAwMH0.hAsrHhTwBhEMO5hw9hZ_bA"

        assertNull(MagisRef.decode(deWeb))
    }

    @Test
    fun `garbage, empty and half-built things give null instead of blowing up`() {
        listOf(
            "",
            "   ",
            "magis1",
            "magis1:movie",
            "magis1:movie:0:",
            "no-es-un-ref",
            "sinpunto",
            "...",
            "@@@.@@@",
            "eyJzIjoibWFnaXMi.x", // truncated json
        ).forEach { assertNull("ref $it", MagisRef.decode(it)) }
    }

    @Test
    fun `with no program_type it's assumed to be a movie`() {
        assertEquals("movie", MagisRef.decode("magis1::0:C1")?.programType)
        assertTrue(MagisRef.decode("magis1::0:C1")?.isSeries == false)
    }

    @Test
    fun `an episode that isn't a number doesn't sink the ref`() {
        val ref = MagisRef.decode("magis1:teleplay:tres:C1")!!

        assertEquals(0, ref.episode)
        assertEquals("C1", ref.contentId)
    }

    @Test
    fun `the portal's three series types count as a series`() {
        listOf("teleplay", "series", "variety").forEach {
            assertTrue(it, MagisRef(contentId = "x", programType = it).isSeries)
        }
        assertTrue(!MagisRef(contentId = "x", programType = "movie").isSeries)
    }
}
