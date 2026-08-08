package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El caso real que motivó todo esto (verificado en el celular): DAN DA DAN quedó guardada bajo dos
 * ítems distintos y el MISMO capítulo (mismo hash de pageUrl, `31fe74c5`) aparecía `completed` en
 * uno y `queued` en el otro — 461 MB a punto de bajarse por segunda vez.
 */
class DuplicateDownloadPolicyTest {

    private val yaBajado = EpisodeOrigin("web:series:tt30217403::31fe74c5", torrentFileIndex = null)
    private val elDuplicado = EpisodeOrigin("web:series:anilist171018::31fe74c5", torrentFileIndex = null)

    @Test
    fun `el mismo capitulo web bajo otro item es duplicado`() {
        assertEquals(
            yaBajado.episodeId,
            DuplicateDownloadPolicy.completedDuplicateOf(elDuplicado, listOf(yaBajado)),
        )
    }

    @Test
    fun `otro capitulo de la misma serie no es duplicado`() {
        val otroCapitulo = EpisodeOrigin("web:series:anilist171018::aa11bb22", torrentFileIndex = null)
        assertNull(DuplicateDownloadPolicy.completedDuplicateOf(otroCapitulo, listOf(yaBajado)))
    }

    @Test
    fun `sin nada descargado no hay duplicado`() {
        assertNull(DuplicateDownloadPolicy.completedDuplicateOf(elDuplicado, emptyList()))
    }

    /** Que ESTE episodio ya esté bajado lo resuelve la cola, no esta detección entre ítems. */
    @Test
    fun `no se considera duplicado de si mismo`() {
        assertNull(DuplicateDownloadPolicy.completedDuplicateOf(yaBajado, listOf(yaBajado)))
    }

    // --- Torrent: el sufijo es el infohash, y el archivo elegido va aparte -----------------------

    @Test
    fun `el mismo torrent y archivo bajo otro item es duplicado`() {
        val bajado = EpisodeOrigin("torrent:series:tt30217403::abc123", torrentFileIndex = 4)
        val duplicado = EpisodeOrigin("torrent:anime:171018::abc123", torrentFileIndex = 4)
        assertEquals(
            bajado.episodeId,
            DuplicateDownloadPolicy.completedDuplicateOf(duplicado, listOf(bajado)),
        )
    }

    /** Mismo pack, archivo distinto = capítulo distinto: bajar de más antes que bloquear de menos. */
    @Test
    fun `el mismo torrent con otro archivo no es duplicado`() {
        val bajado = EpisodeOrigin("torrent:series:tt30217403::abc123", torrentFileIndex = 4)
        val otro = EpisodeOrigin("torrent:anime:171018::abc123", torrentFileIndex = 7)
        assertNull(DuplicateDownloadPolicy.completedDuplicateOf(otro, listOf(bajado)))
    }

    /** Los magnet no guardan índice de archivo (es null en los dos): siguen siendo el mismo capítulo. */
    @Test
    fun `dos magnet del mismo infohash son duplicado`() {
        val bajado = EpisodeOrigin("torrent:series:tt30217403::abc123", torrentFileIndex = null)
        val duplicado = EpisodeOrigin("torrent:series:tmdb240411::abc123", torrentFileIndex = null)
        assertNotNull(DuplicateDownloadPolicy.completedDuplicateOf(duplicado, listOf(bajado)))
    }

    // --- Fuentes donde el mismo contenido NO puede estar bajo dos ítems --------------------------

    /**
     * Estos ids no tienen clave a propósito: en archive.org el itemId ES el identifier (único) y en
     * las películas web/torrent sueltas el itemId ya es el hash del origen, así que el mismo
     * contenido da siempre el mismo episodeId. Y compararlos sería peor que no hacer nada: en
     * `torrent:<hash>::<índice>` el sufijo es un índice de archivo, que colisiona entre torrents
     * distintos (todos tienen un archivo 0) y haría pasar por "ya descargado" a otra película.
     */
    @Test
    fun `archive y las peliculas sueltas no entran en la deteccion`() {
        assertNull(DuplicateDownloadPolicy.originKeyOf(EpisodeOrigin("dragon-ball-gt::ep01.mp4", null)))
        assertNull(DuplicateDownloadPolicy.originKeyOf(EpisodeOrigin("web:9f2a1b::0", null)))
        assertNull(DuplicateDownloadPolicy.originKeyOf(EpisodeOrigin("torrent:abc123::0", 0)))
    }

    @Test
    fun `un id sin sufijo no tiene clave`() {
        assertNull(DuplicateDownloadPolicy.originKeyOf(EpisodeOrigin("web:series:tt30217403", null)))
        assertNull(DuplicateDownloadPolicy.originKeyOf(EpisodeOrigin("", null)))
    }

    /** Web y torrent no se cruzan aunque el sufijo coincidiera por casualidad. */
    @Test
    fun `las claves de web y torrent no colisionan`() {
        val web = DuplicateDownloadPolicy.originKeyOf(EpisodeOrigin("web:series:tt1::abc", null))
        val torrent = DuplicateDownloadPolicy.originKeyOf(EpisodeOrigin("torrent:series:tt1::abc", null))
        assertNotNull(web)
        assertNotNull(torrent)
        assert(web != torrent)
    }

    /**
     * El caso EXACTO del dispositivo: la fila duplicada ya estaba `queued` desde antes del fix, así
     * que ningún `enqueue` la va a volver a evaluar. La compuerta del worker la agarra justo antes
     * de marcarla `downloading`, que es la última oportunidad de no bajar 461 MB de nuevo.
     */
    @Test
    fun `la fila que ya estaba encolada tambien se detecta`() {
        val yaEnCola = EpisodeOrigin("web:series:anilist171018::31fe74c5", torrentFileIndex = null)
        assertEquals(
            "web:series:tt30217403::31fe74c5",
            DuplicateDownloadPolicy.completedDuplicateOf(yaEnCola, listOf(yaBajado)),
        )
    }

    // --- Borrado del archivo compartido ---------------------------------------------------------

    /**
     * Al adoptar el archivo del gemelo, dos filas apuntan al MISMO `filePath`. Quitar una no puede
     * borrar el archivo mientras la otra lo siga referenciando: la dejaría diciendo "Listo" sobre
     * algo que ya no está.
     */
    @Test
    fun `no se borra el archivo si otra fila lo referencia`() {
        assertEquals(true, DuplicateDownloadPolicy.canDeleteFile(emptyList()))
        assertEquals(false, DuplicateDownloadPolicy.canDeleteFile(listOf("web:series:tt30217403::31fe74c5")))
    }

    // --- Aviso al usuario -----------------------------------------------------------------------

    @Test
    fun `el aviso solo aparece si se salteo algo`() {
        assertNull(DuplicateDownloadPolicy.skippedNotice(0))
        assertNull(DuplicateDownloadPolicy.skippedNotice(-1))
        assertEquals("Ya lo tenés descargado en el dispositivo", DuplicateDownloadPolicy.skippedNotice(1))
        assertEquals(
            "12 capítulos ya estaban descargados en el dispositivo",
            DuplicateDownloadPolicy.skippedNotice(12),
        )
    }
}
