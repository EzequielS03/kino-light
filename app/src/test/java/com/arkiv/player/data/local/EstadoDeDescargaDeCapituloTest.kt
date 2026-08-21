package com.arkiv.player.data.local

import com.arkiv.player.data.db.DownloadRow
import org.junit.Assert.assertEquals
import org.junit.Test

class EstadoDeDescargaDeCapituloTest {

    private fun fila(
        state: String,
        progress: Float = 0f,
        bytes: Long = 0,
        error: String? = null,
    ) = DownloadRow(
        episodeId = "magis:abc::e1",
        itemId = "magis:abc",
        itemTitle = "Daima",
        displayName = "E1",
        thumbPath = null,
        state = state,
        progress = progress,
        localUri = null,
        bytes = bytes,
        source = "magis",
        error = error,
        bytesDone = 0,
    )

    @Test
    fun `sin fila en la tabla no hay descarga`() {
        assertEquals(EstadoDeDescarga.SinDescargar, EstadoDeDescargaDeCapitulo.de(null))
    }

    @Test
    fun `encolada espera turno`() {
        assertEquals(EstadoDeDescarga.EnCola, EstadoDeDescargaDeCapitulo.de(fila(LocalDownloadState.QUEUED)))
    }

    @Test
    fun `bajando con tamano conocido reporta la fraccion`() {
        val estado = EstadoDeDescargaDeCapitulo.de(
            fila(LocalDownloadState.DOWNLOADING, progress = 0.42f, bytes = 1_000L),
        )
        assertEquals(EstadoDeDescarga.Bajando(0.42f), estado)
    }

    @Test
    fun `bajando sin tamano conocido no inventa un porcentaje`() {
        val estado = EstadoDeDescargaDeCapitulo.de(fila(LocalDownloadState.DOWNLOADING, bytes = 0))
        assertEquals(EstadoDeDescarga.Bajando(null), estado)
    }

    @Test
    fun `el staging del servidor va sin porcentaje`() {
        assertEquals(
            EstadoDeDescarga.Bajando(null),
            EstadoDeDescargaDeCapitulo.de(fila(LocalDownloadState.STAGING, progress = 0.5f, bytes = 10)),
        )
    }

    @Test
    fun `completada esta lista`() {
        assertEquals(EstadoDeDescarga.Lista, EstadoDeDescargaDeCapitulo.de(fila(LocalDownloadState.COMPLETED)))
    }

    @Test
    fun `una completada con motivo sigue estando lista`() {
        // El "error" de una fila completada es el motivo por el que no hubo que bajar nada
        // (DuplicateDownloadPolicy.ADOPTED_REASON), no un fallo.
        val estado = EstadoDeDescargaDeCapitulo.de(
            fila(LocalDownloadState.COMPLETED, error = DuplicateDownloadPolicy.ADOPTED_REASON),
        )
        assertEquals(EstadoDeDescarga.Lista, estado)
    }

    @Test
    fun `fallida conserva el motivo`() {
        val estado = EstadoDeDescargaDeCapitulo.de(
            fila(LocalDownloadState.FAILED, error = "Este episodio no tiene un archivo descargable"),
        )
        assertEquals(EstadoDeDescarga.Fallida("Este episodio no tiene un archivo descargable"), estado)
    }

    @Test
    fun `el torrent pesado pide confirmacion y no se muestra como si estuviera bajando`() {
        assertEquals(
            EstadoDeDescarga.PideConfirmacion,
            EstadoDeDescargaDeCapitulo.de(fila(LocalDownloadState.NEEDS_CONFIRMATION, bytes = 9_000_000_000L)),
        )
    }
}
