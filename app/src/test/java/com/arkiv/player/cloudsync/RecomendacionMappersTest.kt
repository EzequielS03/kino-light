package com.arkiv.player.cloudsync

import com.arkiv.player.data.db.RecomendacionEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `recomendaciones` es SOLO LECTURA: el gateway la escribe (recalcula el historial y decide qué
 * mostrar en la fila "Para ti"), la app nunca sube nada acá. Por eso no hay `recomendacionToFields`
 * -- solo `recordToRecomendacion`, la mitad que hace falta para que el pull/SSE existente
 * (`CloudSyncManager`) la traiga a Room.
 *
 * La clave local es el `id` de PocketBase y NO `orden`: el gateway RECREA la lista entera en cada
 * generación (`arkiv-api/recomendaciones/almacen.py::guardar`) -- crea filas nuevas y entierra las
 * viejas con el mismo `updatedAt`, así que dos tandas distintas pueden compartir el mismo `orden`
 * (0..9) con `id`s distintos. Estos tests fijan ese contrato.
 */
class RecomendacionMappersTest {

    private fun json(
        id: String = "rec1",
        tmdbId: Int = 603,
        tipo: String = "movie",
        titulo: String = "The Matrix",
        posterUrl: String = "https://x/matrix.jpg",
        porque: String = "porque terminaste Dragon Ball",
        ref: String = "torrent:abc123",
        orden: Int = 0,
        generadoAt: Long = 1000L,
        updatedAt: Long = 2000L,
        deleted: Boolean = false,
    ) = JSONObject(
        mapOf(
            "id" to id,
            "tmdbId" to tmdbId,
            "tipo" to tipo,
            "titulo" to titulo,
            "posterUrl" to posterUrl,
            "porque" to porque,
            "ref" to ref,
            "orden" to orden,
            "generadoAt" to generadoAt,
            "updatedAt" to updatedAt,
            "deleted" to deleted,
        ),
    )

    @Test fun recomendacion_ida_y_vuelta_conserva_todos_los_campos() {
        val e = recordToRecomendacion(json())
        assertEquals("rec1", e.id)
        assertEquals(603, e.tmdbId)
        assertEquals("movie", e.tipo)
        assertEquals("The Matrix", e.titulo)
        assertEquals("https://x/matrix.jpg", e.posterUrl)
        assertEquals("porque terminaste Dragon Ball", e.porque)
        assertEquals("torrent:abc123", e.ref)
        assertEquals(0, e.orden)
        assertEquals(1000L, e.generadoAt)
        assertEquals(2000L, e.updatedAt)
        assertFalse(e.deleted)
    }

    /** El gateway puede mandar `tmdbId=0` (candidato sin match confirmado en TMDB, p. ej.): no es
     * "no vino", es un valor legítimo y no puede perderse ni tratarse como ausente. */
    @Test fun tmdbId_en_cero_no_se_pierde() {
        val e = recordToRecomendacion(json(tmdbId = 0))
        assertEquals(0, e.tmdbId)
    }

    /** Mismo caso que `tmdbId`: `posterUrl` vacío es un valor legítimo, no una ausencia. */
    @Test fun posterUrl_vacio_no_se_pierde() {
        val e = recordToRecomendacion(json(posterUrl = ""))
        assertEquals("", e.posterUrl)
    }

    @Test fun un_tombstone_llega_marcado_borrado() {
        val e = recordToRecomendacion(json(deleted = true, updatedAt = 9999L))
        assertTrue("un borrado más nuevo tiene que llegar borrado", e.deleted)
        assertEquals(9999L, e.updatedAt)
    }

    /**
     * El merge en `CloudSyncManager.mergeRecomendacion` es: mapear, comparar LWW por `updatedAt`
     * contra la fila local (buscada por `id`), y si el remoto gana, hacer upsert -- MISMO patrón que
     * `mergeMarker`/`mergeLiveFavorite`. Estos dos tests fijan las dos mitades de esa regla usando
     * las mismas piezas que usa la producción (`recordToRecomendacion` + `LwwMerge.pickWinner`), sin
     * necesidad de instanciar `CloudSyncManager` completo (no hay infraestructura de Room en los
     * tests unitarios de este módulo).
     */
    @Test fun una_actualizacion_vieja_no_pisa_a_una_mas_nueva() {
        val local = RecomendacionEntity(
            id = "rec1", tmdbId = 603, tipo = "movie", titulo = "The Matrix",
            posterUrl = "https://x/matrix.jpg", porque = "porque sí", ref = "torrent:abc",
            orden = 0, generadoAt = 1000L, updatedAt = 5000L, deleted = false,
        )
        val remoto = recordToRecomendacion(json(updatedAt = 1000L))
        assertFalse(
            "el remoto es más viejo que lo local: no debería ganar el LWW",
            LwwMerge.pickWinner(local.updatedAt, remoto.updatedAt),
        )
    }

    @Test fun un_tombstone_mas_nuevo_gana_y_borra_localmente() {
        val local = RecomendacionEntity(
            id = "rec1", tmdbId = 603, tipo = "movie", titulo = "The Matrix",
            posterUrl = "https://x/matrix.jpg", porque = "porque sí", ref = "torrent:abc",
            orden = 0, generadoAt = 1000L, updatedAt = 2000L, deleted = false,
        )
        val remoto = recordToRecomendacion(json(deleted = true, updatedAt = 3000L))
        assertTrue(
            "el tombstone es más nuevo: tiene que ganar el LWW",
            LwwMerge.pickWinner(local.updatedAt, remoto.updatedAt),
        )
        assertTrue("y lo que gana es un borrado", remoto.deleted)
    }
}
