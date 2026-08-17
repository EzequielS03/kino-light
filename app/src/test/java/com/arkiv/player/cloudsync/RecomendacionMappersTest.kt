package com.arkiv.player.cloudsync

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

    // La regla de merge (LWW contra la fila local + upsert si gana el remoto) NO se prueba acá
    // combinando `recordToRecomendacion` y `LwwMerge.pickWinner` por separado -- eso deja pasar un
    // guard roto en la función real sin que ningún test se entere (hallazgo de la revisión del
    // 2026-08-17). Ver MergeRecomendacionTest, que ejercita `mergeRecomendacion` de verdad.
}
