package com.arkiv.player.miniaturas

import com.arkiv.player.data.db.EpisodeFrameDao
import com.arkiv.player.data.db.EpisodeFrameEntity
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** Fake mínimo en memoria: alcanza con lo que [DestructorDeFrames] usa. */
    private class FakeEpisodeFrameDao : EpisodeFrameDao {
        val filas = mutableMapOf<String, EpisodeFrameEntity>()

        override suspend fun upsert(frame: EpisodeFrameEntity) {
            filas[frame.episodeId] = frame
        }

        override suspend fun get(episodeId: String): EpisodeFrameEntity? =
            filas[episodeId]?.takeIf { it.deleted == 0 }

        override suspend fun getIncluyendoBorradas(episodeId: String): EpisodeFrameEntity? = filas[episodeId]

        override suspend fun getFramesSince(cursor: Long): List<EpisodeFrameEntity> =
            filas.values.filter { it.updatedAt > cursor }.sortedBy { it.updatedAt }

        override suspend fun pendientesDeBajar(): List<EpisodeFrameEntity> =
            filas.values.filter { it.deleted == 0 && it.remoteUrl != null }

        override fun observeForItem(itemId: String) = MutableStateFlow(emptyList<EpisodeFrameEntity>())

        override suspend fun borrarTodo() {
            filas.clear()
        }
    }

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
