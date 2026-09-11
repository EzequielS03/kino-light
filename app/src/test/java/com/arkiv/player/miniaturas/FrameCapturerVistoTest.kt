package com.arkiv.player.miniaturas

import com.arkiv.player.data.db.ContinueRow
import com.arkiv.player.data.db.FilaDeHistorial
import com.arkiv.player.data.db.PlaybackDao
import com.arkiv.player.data.db.UltimaReproduccionRow
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.db.ProgresoConSiguienteRow
import com.arkiv.player.data.db.VistoRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A chapter that's ALREADY WATCHED can't be left with a frame.
 *
 * On leaving the player, `onDispose` fires `saveProgress` (which marks watched and destroys the
 * frame past 60%) and the capture right after; since compressing the JPEG costs tens of ms, the
 * capture lands last and left the finished chapter with a LIVE frame nobody was going to delete.
 * Before Task 5 that would also get uploaded to PocketBase and propagated; without cloud sync it's
 * now an orphan local frame, still exactly what this guard exists to stop.
 *
 * Se ejerce [FrameCapturer.publicar] y no `capturar`: esa necesita un `TextureView`, que no existe
 * fuera de un dispositivo. Es el mismo punto donde vive la guarda.
 */
class FrameCapturerVistoTest {

    @get:Rule val temp = TemporaryFolder()

    /** Fake mínimo: [FrameCapturer] solo consulta [get]. */
    private class FakePlaybackDao : PlaybackDao {
        val filas = mutableMapOf<String, PlaybackEntity>()

        override suspend fun upsert(playback: PlaybackEntity) { filas[playback.episodeId] = playback }
        override suspend fun get(episodeId: String): PlaybackEntity? = filas[episodeId]
        override fun observe(episodeId: String): Flow<PlaybackEntity?> = MutableStateFlow(filas[episodeId])
        override fun observeProgresoConSiguiente(): Flow<List<ProgresoConSiguienteRow>> =
            MutableStateFlow(emptyList())
        override suspend fun filasParaContinuar(episodeIds: List<String>): List<ContinueRow> = emptyList()
        override fun observeVistos(): Flow<List<VistoRow>> = MutableStateFlow(emptyList())
        override fun observePlaybackForItem(itemId: String): Flow<List<PlaybackEntity>> =
            MutableStateFlow(emptyList())
        // Lo agregó otra rama (orden de la biblioteca por lo último visto) mientras esta estaba
        // abierta. FrameCapturer no lo usa; está solo para que el fake siga implementando el DAO.
        override fun observeUltimaReproduccion(): Flow<List<UltimaReproduccionRow>> =
            MutableStateFlow(emptyList())
        // Lo agregó la Task 7 (historial local para "Para ti"). FrameCapturer no lo usa; está solo
        // para que el fake siga implementando el DAO.
        override suspend fun historialReciente(tope: Int): List<FilaDeHistorial> = emptyList()
    }

    private fun progreso(episodeId: String, watched: Boolean) = PlaybackEntity(
        episodeId = episodeId, positionMs = 60_000, durationMs = 100_000, watched = watched,
        lastPlayedAt = 1L, updatedAt = 1L,
    )

    private fun capturer(almacen: AlmacenDeFrames, frames: FakeEpisodeFrameDao, playback: FakePlaybackDao) =
        FrameCapturer(almacen, frames, playback) { 777L }

    @Test fun `no escribe nada si el capitulo ya esta visto`() = runBlocking {
        val almacen = AlmacenDeFrames(temp.newFolder("frames"))
        val frames = FakeEpisodeFrameDao()
        val playback = FakePlaybackDao().apply { filas["ep-1"] = progreso("ep-1", watched = true) }

        val guardado = capturer(almacen, frames, playback).publicar("ep-1", 90_000, byteArrayOf(1, 2, 3))

        assertFalse("la captura tiene que reportar que no guardó", guardado)
        assertNull("ni el JPEG", almacen.rutaSiExiste("ep-1"))
        assertTrue("ni la fila: un capítulo terminado no tiene frame", frames.filas.isEmpty())
    }

    @Test fun `un capitulo a medias si captura`() = runBlocking {
        val almacen = AlmacenDeFrames(temp.newFolder("frames"))
        val frames = FakeEpisodeFrameDao()
        val playback = FakePlaybackDao().apply { filas["ep-1"] = progreso("ep-1", watched = false) }

        val guardado = capturer(almacen, frames, playback).publicar("ep-1", 90_000, byteArrayOf(1, 2, 3))

        assertTrue(guardado)
        assertNotNull(almacen.rutaSiExiste("ep-1"))
        assertEquals(90_000L, frames.filas.getValue("ep-1").positionMs)
        assertEquals(777L, frames.filas.getValue("ep-1").updatedAt)
        assertEquals("una captura local nunca nace marcada como remota", 0, frames.filas.getValue("ep-1").origenRemoto)
    }

    /** Sin fila de progreso (el primer play) tampoco hay nada que impida capturar. */
    @Test fun `sin fila de progreso captura igual`() = runBlocking {
        val almacen = AlmacenDeFrames(temp.newFolder("frames"))
        val frames = FakeEpisodeFrameDao()

        val guardado = capturer(almacen, frames, FakePlaybackDao()).publicar("ep-1", 5_000, byteArrayOf(9))

        assertTrue(guardado)
        assertNotNull(almacen.rutaSiExiste("ep-1"))
    }
}
