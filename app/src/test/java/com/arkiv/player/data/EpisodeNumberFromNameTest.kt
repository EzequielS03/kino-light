package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sacar (temporada, capítulo) del nombre del archivo.
 *
 * Es lo que permite ponerle a cada capítulo su título real de TMDB en vez de "s01e03": ni
 * archive.org ni nuestra base guardan el nombre del episodio, solo su número. Se parsea del
 * nombre —y no se pide al mirror— para que funcione igual con ítems públicos de archive.org
 * que nunca pasaron por nosotros.
 */
class EpisodeNumberFromNameTest {

    private fun n(s: String) = MetadataParser.episodeNumberOf(s)

    @Test
    fun `patron sNNeNN, que es como nombramos nuestras subidas`() {
        assertEquals(1 to 3, n("f75163f026d99259e37c_12697_s01e03.mp4"))
        assertEquals(1 to 27, n("s01e27.es-419.720p.mp4"))
    }

    @Test
    fun `no depende de mayusculas ni de la extension`() {
        assertEquals(2 to 5, n("Serie.S02E05.1080p.mkv"))
        assertEquals(2 to 5, n("serie s02e05"))
    }

    @Test
    fun `patron NxNN, comun en releases en espanol`() {
        assertEquals(1 to 3, n("Dragon Ball GT 1x03.mp4"))
        assertEquals(12 to 4, n("serie 12x04.mkv"))
    }

    @Test
    fun `numeros de tres digitos no se truncan`() {
        assertEquals(1 to 1024, n("One Piece S01E1024.mkv"))
    }

    @Test
    fun `sin patron reconocible devuelve null en vez de inventar un numero`() {
        assertNull(n("Evangelion_01.mkv"))          // suelto, sin temporada: ambiguo
        assertNull(n("intro.mp4"))
        assertNull(n(""))
    }

    @Test
    fun `no confunde una resolucion con un patron de capitulo`() {
        // "1920x1080" tiene forma de NxNN; tomarlo daría temporada 1920.
        assertNull(n("pelicula 1920x1080.mp4"))
    }

    @Test
    fun `el parseo llega hasta el episodio armado`() {
        val files = listOf(
            RawFileFixture.video("show_s01e03.mp4"),
            RawFileFixture.video("show_s01e04.mp4"),
        )
        val eps = MetadataParser.parse("show", "T", null, "thumb", files).episodes
        assertEquals(listOf(1 to 3, 1 to 4), eps.map { it.season to it.episode })
    }

    @Test
    fun `un item sin numeracion deja los campos en null`() {
        val eps = MetadataParser
            .parse("id", "T", null, "thumb", listOf(RawFileFixture.video("intro.mp4")))
            .episodes
        assertNull(eps.first().season)
        assertNull(eps.first().episode)
    }
}

private object RawFileFixture {
    fun video(name: String) = com.arkiv.player.data.model.RawFile(
        name = name, source = "original", format = "MPEG4",
        original = null, sizeBytes = 100, lengthSeconds = 10.0,
    )
}
