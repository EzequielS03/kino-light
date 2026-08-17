package com.arkiv.player.cloudsync

import com.arkiv.player.data.db.RecomendacionEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `mergeRecomendacion` es la regla central del sync de `recomendaciones`: LWW por `updatedAt` y
 * tombstone. `RecomendacionMappersTest` prueba `recordToRecomendacion` y `LwwMerge.pickWinner` cada
 * uno por su lado, pero eso NUNCA ejercita la función real que los combina -- la revisión del
 * 2026-08-17 lo demostró quitando el guard `if (!LwwMerge.pickWinner(...)) return false` de
 * `mergeRecomendacion` (dejando un upsert incondicional) y corriendo la suite completa: 0 tests
 * fallaron. Estos son esos tests que faltaban.
 *
 * `mergeRecomendacion` vive como función top-level en CloudSyncManager.kt (ver su KDoc) en vez de
 * método privado de la clase, justamente para poder llamarla acá con un [FakeRecomendacionDao] sin
 * tener que construir un `CloudSyncManager` completo -- la clase real además pide `PbSyncClient`,
 * `PocketBaseRealtime`, `DeviceAuthManager`, `SyncCursors` y `SyncQuarantine`, todas con `Context`
 * de Android, y este módulo no tiene Robolectric en sus tests unitarios de JVM.
 *
 * Verificado por mutación (no solo por inspección): quitando a mano el guard
 * `if (!LwwMerge.pickWinner(...)) return false` de `mergeRecomendacion`,
 * `una_actualizacion_vieja_no_pisa_a_una_mas_nueva` FALLA (era el test que la revisión echó en
 * falta); restaurado el guard, vuelve a pasar. Los otros tres tests de esta clase no dependen de
 * ESE guard en particular -- prueban tombstone, actualización legítima y fila nueva -- así que no
 * son los que lo detectan, pero documentan el resto del contrato de la función.
 */
class MergeRecomendacionTest {

    private fun entidad(
        id: String = "rec1",
        orden: Int = 0,
        updatedAt: Long = 0L,
        deleted: Boolean = false,
    ) = RecomendacionEntity(
        id = id, tmdbId = 603, tipo = "movie", titulo = "The Matrix",
        posterUrl = "https://x/matrix.jpg", porque = "porque sí", ref = "torrent:abc",
        orden = orden, generadoAt = 0L, updatedAt = updatedAt, deleted = deleted,
    )

    private fun json(
        id: String = "rec1",
        updatedAt: Long,
        deleted: Boolean = false,
        titulo: String = "The Matrix (remoto)",
    ) = JSONObject(
        mapOf(
            "id" to id, "tmdbId" to 603, "tipo" to "movie", "titulo" to titulo,
            "posterUrl" to "https://x/matrix.jpg", "porque" to "porque sí", "ref" to "torrent:abc",
            "orden" to 0, "generadoAt" to 0L, "updatedAt" to updatedAt, "deleted" to deleted,
        ),
    )

    @Test fun una_actualizacion_vieja_no_pisa_a_una_mas_nueva() = runBlocking {
        val dao = FakeRecomendacionDao()
        dao.upsert(entidad(updatedAt = 5000L))

        val aplicado = mergeRecomendacion(dao, json(updatedAt = 1000L), remoteUpdatedAt = 1000L)

        assertFalse("el remoto es más viejo: el merge no debería aplicarlo", aplicado)
        assertEquals(
            "la fila local tiene que seguir intacta",
            5000L,
            dao.get("rec1")!!.updatedAt,
        )
    }

    @Test fun un_tombstone_mas_nuevo_gana_y_borra_localmente() = runBlocking {
        val dao = FakeRecomendacionDao()
        dao.upsert(entidad(updatedAt = 1000L, deleted = false))

        val aplicado = mergeRecomendacion(dao, json(updatedAt = 2000L, deleted = true), remoteUpdatedAt = 2000L)

        assertTrue("el tombstone es más nuevo: el merge tiene que aplicarlo", aplicado)
        assertTrue("y la fila local queda borrada", dao.get("rec1")!!.deleted)
    }

    @Test fun una_actualizacion_mas_nueva_si_pisa_a_la_vieja() = runBlocking {
        val dao = FakeRecomendacionDao()
        dao.upsert(entidad(updatedAt = 1000L))

        val aplicado = mergeRecomendacion(
            dao, json(updatedAt = 2000L, titulo = "The Matrix Reloaded"), remoteUpdatedAt = 2000L,
        )

        assertTrue(aplicado)
        assertEquals("The Matrix Reloaded", dao.get("rec1")!!.titulo)
    }

    @Test fun sin_fila_local_el_remoto_se_aplica_siempre() = runBlocking {
        val dao = FakeRecomendacionDao()

        val aplicado = mergeRecomendacion(dao, json(id = "nueva", updatedAt = 1L), remoteUpdatedAt = 1L)

        assertTrue("no había nada local: el remoto tiene que entrar", aplicado)
        assertEquals("The Matrix (remoto)", dao.get("nueva")!!.titulo)
    }
}
