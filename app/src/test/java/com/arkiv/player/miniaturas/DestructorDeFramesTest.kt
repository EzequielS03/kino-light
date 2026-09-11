package com.arkiv.player.miniaturas

import com.arkiv.player.data.db.EpisodeFrameEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `destruir` deja tombstone (no `DELETE`) para que el borrado viaje por el sync; `destruirTodo`
 * (wipe de logout) sigue siendo un `DELETE` físico a propósito — ver el doc de la clase.
 */
class DestructorDeFramesTest {

    @get:Rule val temp = TemporaryFolder()

    private fun almacen() = AlmacenDeFrames(temp.newFolder("frames"))

    @Test
    fun `destruir deja tombstone en vez de borrar la fila`() = runBlocking {
        val dao = FakeEpisodeFrameDao()
        dao.filas["ep-1"] = EpisodeFrameEntity(
            episodeId = "ep-1", positionMs = 90_000, capturedAt = 10, updatedAt = 10, deleted = 0,
        )
        val destructor = DestructorDeFrames(dao = dao, ahora = { 999L })

        destructor.destruir("ep-1")

        val fila = dao.getIncluyendoBorradas("ep-1")
        assertTrue("la fila tiene que seguir existiendo (tombstone, no DELETE)", fila != null)
        assertEquals(1, fila!!.deleted)
        assertEquals(999L, fila.updatedAt)
    }

    @Test
    fun `destruir crea tombstone aunque no hubiera fila previa`() = runBlocking {
        val dao = FakeEpisodeFrameDao()
        val destructor = DestructorDeFrames(dao = dao, ahora = { 5L })

        destructor.destruir("ep-sin-frame")

        val fila = dao.getIncluyendoBorradas("ep-sin-frame")
        assertEquals(1, fila?.deleted)
        assertEquals(5L, fila?.updatedAt)
    }

    @Test
    fun `destruir borra el archivo del almacen`() = runBlocking {
        val almacen = almacen()
        val dao = FakeEpisodeFrameDao()
        almacen.guardar("ep-1", byteArrayOf(1, 2, 3))
        val destructor = DestructorDeFrames(almacen, dao) { 1L }

        destructor.destruir("ep-1")

        assertNull(almacen.rutaSiExiste("ep-1"))
    }

    /**
     * Regression from a critical review finding: `savePlayback` calls `destruir` on EVERY player
     * tick (~5 s) while the chapter stays watched, with no guard of its own. If every call
     * rewrote `updatedAt` with the clock at that moment, it would needlessly invalidate the
     * `episode_frame`-driven "Continue watching" Flow the rest of the chapter -- and, before
     * Task 5, would also have kept re-queuing the same row for the push to PocketBase.
     */
    @Test
    fun `destruir dos veces seguidas no reescribe el updatedAt la segunda vez`() = runBlocking {
        val dao = FakeEpisodeFrameDao()
        var tiempoActual = 100L
        val destructor = DestructorDeFrames(dao = dao, ahora = { tiempoActual })

        destructor.destruir("ep-1")
        assertEquals(100L, dao.getIncluyendoBorradas("ep-1")?.updatedAt)

        tiempoActual = 999L // si destruir() volviera a tomar el reloj, el updatedAt cambiaría
        destructor.destruir("ep-1")

        assertEquals(
            "el tombstone ya estaba sellado: la segunda llamada no debe tocar updatedAt",
            100L,
            dao.getIncluyendoBorradas("ep-1")?.updatedAt,
        )
    }

    @Test
    fun `destruir borra un archivo huerfano aunque la fila ya sea tombstone`() = runBlocking {
        val almacen = almacen()
        val dao = FakeEpisodeFrameDao()
        dao.filas["ep-1"] = EpisodeFrameEntity(
            episodeId = "ep-1", positionMs = 0, capturedAt = 0, updatedAt = 50, deleted = 1,
        )
        almacen.guardar("ep-1", byteArrayOf(9)) // archivo huérfano: la fila ya estaba borrada
        val destructor = DestructorDeFrames(almacen, dao) { 999L }

        destructor.destruir("ep-1")

        assertNull(
            "el archivo huérfano se borra igual, aunque la fila ya fuera tombstone",
            almacen.rutaSiExiste("ep-1"),
        )
        assertEquals("y el updatedAt de la fila no se toca", 50L, dao.getIncluyendoBorradas("ep-1")?.updatedAt)
    }

    @Test
    fun `get normal no ve el tombstone que dejo destruir`() = runBlocking {
        val dao = FakeEpisodeFrameDao()
        val destructor = DestructorDeFrames(dao = dao, ahora = { 1L })

        destructor.destruir("ep-1")

        assertNull("get() filtra deleted = 0: un tombstone se ve como inexistente", dao.get("ep-1"))
    }

    @Test
    fun `destruirTodo hace DELETE fisico, no deja tombstones`() = runBlocking {
        val dao = FakeEpisodeFrameDao()
        dao.filas["ep-1"] = EpisodeFrameEntity(
            episodeId = "ep-1", positionMs = 1, capturedAt = 1, updatedAt = 1, deleted = 0,
        )
        dao.filas["ep-2"] = EpisodeFrameEntity(
            episodeId = "ep-2", positionMs = 1, capturedAt = 1, updatedAt = 1, deleted = 0,
        )
        val destructor = DestructorDeFrames(dao = dao)

        destructor.destruirTodo()

        assertTrue("no puede quedar NADA, ni siquiera tombstones", dao.filas.isEmpty())
    }
}
