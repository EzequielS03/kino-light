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
}
