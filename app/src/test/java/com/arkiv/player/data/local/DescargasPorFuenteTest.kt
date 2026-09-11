package com.arkiv.player.data.local

import com.arkiv.player.data.db.DownloadRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DescargasPorFuenteTest {

    private fun fila(
        episodeId: String,
        itemId: String = "item",
        state: String = LocalDownloadState.DOWNLOADING,
        progress: Float = 0.5f,
        bytes: Long = 100,
        sourceRef: String? = null,
    ) = DownloadRow(
        episodeId = episodeId,
        itemId = itemId,
        itemTitle = "Serie",
        displayName = "E1",
        thumbPath = null,
        state = state,
        progress = progress,
        localUri = null,
        bytes = bytes,
        source = "torrent",
        error = null,
        bytesDone = 0,
        sourceRef = sourceRef,
    )

    @Test
    fun `una fuente web se reconoce por la url de su pagina`() {
        val filas = listOf(fila("web:series:tt1::abc", sourceRef = "https://sitio.com/cap-1"))
        val fila = DescargasPorFuente.deWeb(filas, "https://sitio.com/cap-1")
        assertEquals("web:series:tt1::abc", fila?.episodeId)
        assertEquals(EstadoDeDescarga.Bajando(0.5f), EstadoDeDescargaDeCapitulo.de(fila))
    }

    @Test
    fun `una url que nadie encolo no tiene descarga`() {
        val filas = listOf(fila("web:series:tt1::abc", sourceRef = "https://sitio.com/cap-1"))
        assertNull(DescargasPorFuente.deWeb(filas, "https://otro.com/cap-9"))
    }

    @Test
    fun `un item de archive se reconoce por su identifier`() {
        val filas = listOf(fila("cualquier-archivo.mp4", itemId = "dragon-ball-gt"))
        assertEquals("cualquier-archivo.mp4", DescargasPorFuente.deArchive(filas, "dragon-ball-gt")?.episodeId)
    }

    @Test
    fun `entre varias filas del mismo item gana la que esta mas avanzada`() {
        // Un ítem de archive con varios capítulos encolados: la fila de la fuente resume el ítem, y
        // "una está bajando" es más útil que "una está en cola".
        val filas = listOf(
            fila("a.mp4", itemId = "serie", state = LocalDownloadState.QUEUED),
            fila("b.mp4", itemId = "serie", state = LocalDownloadState.DOWNLOADING, progress = 0.3f),
        )
        val fila = DescargasPorFuente.deArchive(filas, "serie")
        assertEquals("b.mp4", fila?.episodeId)
        assertEquals(EstadoDeDescarga.Bajando(0.3f), EstadoDeDescargaDeCapitulo.de(fila))
    }

    @Test
    fun `si ya esta todo descargado lo dice`() {
        val filas = listOf(
            fila("a.mp4", itemId = "serie", state = LocalDownloadState.COMPLETED),
            fila("b.mp4", itemId = "serie", state = LocalDownloadState.COMPLETED),
        )
        assertEquals(
            EstadoDeDescarga.Lista,
            EstadoDeDescargaDeCapitulo.de(DescargasPorFuente.deArchive(filas, "serie")),
        )
    }
}
