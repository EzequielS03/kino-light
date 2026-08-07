package com.arkiv.player.data

import com.arkiv.player.data.model.RawFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataParserTest {

    private fun video(name: String, source: String, format: String, original: String?, size: Long, len: Double) =
        RawFile(name = name, source = source, format = format, original = original, sizeBytes = size, lengthSeconds = len)

    @Test
    fun `empareja mkv original con su derivado mp4 en un solo episodio`() {
        val files = listOf(
            video("folder/Evangelion_01.mkv", "original", "Matroska", null, 655_000_000, 1416.5),
            video("folder/Evangelion_01.mp4", "derivative", "h.264", "folder/Evangelion_01.mkv", 139_000_000, 1416.5),
        )
        val item = MetadataParser.parse("id", "T", null, "thumb", files)

        assertEquals(1, item.episodes.size)
        val ep = item.episodes.first()
        assertEquals("folder/Evangelion_01.mkv", ep.original?.path)
        assertEquals("folder/Evangelion_01.mp4", ep.derivative?.path)
        assertEquals("folder", ep.section)
    }

    @Test
    fun `saca el identificador del nombre visible del episodio`() {
        // Nuestras subidas nombran cada archivo con el identificador adelante, que es un hash.
        // Sin pelarlo, la lista de capítulos muestra "f75163f026d99259e37c 12697 s01e01".
        val id = "f75163f026d99259e37c_12697"
        val files = listOf(
            video("${id}_s01e01.mp4", "original", "MPEG4", null, 350_000_000, 1420.5),
            video("${id}_s01e02.mp4", "original", "MPEG4", null, 351_000_000, 1418.0),
        )
        val eps = MetadataParser.parse(id, "Dragon Ball GT", null, "thumb", files).episodes
        assertEquals(listOf("s01e01", "s01e02"), eps.map { it.displayName })
    }

    @Test
    fun `un item publico normal conserva su nombre tal cual`() {
        val files = listOf(video("Evangelion_01.mkv", "original", "Matroska", null, 600, 10.0))
        val ep = MetadataParser.parse("otro-item", "T", null, "thumb", files).episodes.first()
        assertEquals("Evangelion 01", ep.displayName)
    }

    @Test
    fun `castVariant prefiere el mp4 y playbackVariant prefiere el mkv`() {
        val files = listOf(
            video("v.mkv", "original", "Matroska", null, 600, 10.0),
            video("v.mp4", "derivative", "h.264", "v.mkv", 100, 10.0),
        )
        val ep = MetadataParser.parse("id", "T", null, "thumb", files).episodes.first()
        assertEquals("v.mkv", ep.playbackVariant?.path)
        assertEquals("v.mp4", ep.castVariant?.path)
    }

    @Test
    fun `ordena por numero de episodio de forma natural (2 antes que 10)`() {
        val files = listOf(
            video("Eva_10.mp4", "original", "h.264", null, 1, 1.0),
            video("Eva_02.mp4", "original", "h.264", null, 1, 1.0),
            video("Eva_01.mp4", "original", "h.264", null, 1, 1.0),
        )
        val eps = MetadataParser.parse("id", "T", null, "thumb", files).episodes
        assertEquals(listOf("Eva 01", "Eva 02", "Eva 10"), eps.map { it.displayName })
        assertEquals(listOf(0, 1, 2), eps.map { it.orderIndex })
    }

    @Test
    fun `ignora thumbnails y archivos no-video`() {
        val files = listOf(
            video("v.mkv", "original", "Matroska", null, 600, 10.0),
            RawFile("v_thumb.jpg", "derivative", "Thumbnail", "v.mkv", 100, 0.0),
            RawFile("meta.xml", "metadata", "Metadata", null, 50, 0.0),
        )
        val item = MetadataParser.parse("id", "T", null, "thumb", files)
        assertEquals(1, item.episodes.size)
        assertEquals("v_thumb.jpg", item.episodes.first().thumbPath)
    }

    @Test
    fun `episodio solo con mp4 derivado tiene original nulo pero es reproducible`() {
        val files = listOf(
            video("only.mp4", "derivative", "h.264", "only.mkv", 100, 5.0),
        )
        val ep = MetadataParser.parse("id", "T", null, "thumb", files).episodes.first()
        assertNull(ep.original)
        assertEquals("only.mp4", ep.derivative?.path)
        assertEquals("only.mp4", ep.playbackVariant?.path)
    }

    @Test
    fun `agrupa por carpeta como seccion`() {
        val files = listOf(
            video("Temporada 1/e1.mp4", "original", "h.264", null, 1, 1.0),
            video("Temporada 2/e1.mp4", "original", "h.264", null, 1, 1.0),
        )
        val eps = MetadataParser.parse("id", "T", null, "thumb", files).episodes
        assertEquals(setOf("Temporada 1", "Temporada 2"), eps.map { it.section }.toSet())
    }

    @Test
    fun `nombre real de Evangelion con @ se limpia legible`() {
        val name = "Evangelion Spanish dubs/TPO_Neon_Genesis_Evangelion_01@Trapo2019_Universo_Anime.mkv"
        val files = listOf(video(name, "original", "Matroska", null, 655_000_000, 1416.5))
        val ep = MetadataParser.parse("id", "T", null, "thumb", files).episodes.first()
        assertEquals("Evangelion Spanish dubs", ep.section)
        assertTrue(ep.displayName.startsWith("TPO Neon Genesis Evangelion 01"))
    }
}
