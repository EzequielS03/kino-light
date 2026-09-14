package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataParserTest {

    @Test
    fun `saca el identificador del nombre visible del episodio`() {
        // Our own uploads named every file with the identifier up front, which is a hash.
        // Without stripping it, the chapter list shows "f75163f026d99259e37c 12697 s01e01".
        val id = "f75163f026d99259e37c_12697"
        assertEquals("s01e01", MetadataParser.cleanName("${id}_s01e01.mp4", id))
        assertEquals("s01e02", MetadataParser.cleanName("${id}_s01e02.mp4", id))
    }

    @Test
    fun `un nombre normal conserva su nombre tal cual`() {
        assertEquals("Evangelion 01", MetadataParser.cleanName("Evangelion_01.mkv", "otro-item"))
    }

    @Test
    fun `el prefijo de carpeta se pela antes de limpiar el nombre`() {
        assertEquals("Evangelion 01", MetadataParser.cleanName("Serie/Season 1/Evangelion_01.mkv", "otro-item"))
    }

    @Test
    fun `nombre con @ se limpia legible`() {
        val name = "TPO_Neon_Genesis_Evangelion_01@Trapo2019_Universo_Anime.mkv"
        assertTrue(MetadataParser.cleanName(name, "id").startsWith("TPO Neon Genesis Evangelion 01"))
    }
}
