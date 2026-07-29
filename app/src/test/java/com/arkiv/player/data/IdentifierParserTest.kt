package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdentifierParserTest {

    private val expected = "tpo-neon-genesis-evangelion-05-trapo-2019-universo-anime"

    @Test
    fun `extrae identifier de URL details con path de archivo`() {
        val url = "https://archive.org/details/$expected/Evangelion+Spanish+dubs/TPO_01.mkv"
        assertEquals(expected, IdentifierParser.extract(url))
    }

    @Test
    fun `extrae identifier de URL download`() {
        assertEquals(expected, IdentifierParser.extract("https://archive.org/download/$expected/x.mp4"))
    }

    @Test
    fun `extrae identifier de URL metadata sin path`() {
        assertEquals(expected, IdentifierParser.extract("https://archive.org/metadata/$expected"))
    }

    @Test
    fun `acepta identifier suelto sin URL`() {
        assertEquals(expected, IdentifierParser.extract("  $expected  "))
    }

    @Test
    fun `ignora query y fragmento`() {
        assertEquals(expected, IdentifierParser.extract("https://archive.org/details/$expected?utm=1#play"))
    }

    @Test
    fun `entrada vacia devuelve null`() {
        assertNull(IdentifierParser.extract("   "))
    }
}
