package com.arkiv.player.cloudsync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El bug que originó esto: el cursor de push avanzaba al máximo `updatedAt` de TODAS las filas,
 * hubieran subido o no. Una fila que fallaba quedaba por debajo del cursor y `getItemsSince()`
 * no la volvía a ver NUNCA (pérdida silenciosa). Verificado en producción: 21 borrados perdidos.
 */
class PushFrontierTest {

    private fun ok(t: Long) = RowOutcome(t, settled = true)
    private fun fail(t: Long) = RowOutcome(t, settled = false)

    @Test fun sin_filas_deja_el_cursor_quieto() {
        assertEquals(100L, PushFrontier.advance(100L, emptyList()))
    }

    @Test fun todas_ok_avanza_al_maximo() {
        assertEquals(30L, PushFrontier.advance(0L, listOf(ok(10), ok(20), ok(30))))
    }

    @Test fun si_falla_la_primera_no_avanza() {
        assertEquals(0L, PushFrontier.advance(0L, listOf(fail(10), ok(20), ok(30))))
    }

    /** El corazón del arreglo: avanza hasta lo que sí subió, pero se detiene ANTES de la fallida. */
    @Test fun se_detiene_antes_de_la_fallida() {
        assertEquals(10L, PushFrontier.advance(0L, listOf(ok(10), fail(20), ok(30))))
    }

    /** Empates de timestamp: si una fila con updatedAt=20 falló, el cursor NO puede quedar en 20,
     * porque el filtro es `updatedAt > cursor` y la fallida se saltaría igual. */
    @Test fun empate_de_timestamp_con_una_fallida_no_avanza_hasta_ese_valor() {
        assertEquals(10L, PushFrontier.advance(0L, listOf(ok(10), ok(20), fail(20), ok(30))))
    }

    /** Una fila en cuarentena (falló K veces, es inválida de forma permanente) cuenta como resuelta:
     * si no, atascaría la colección para siempre. */
    @Test fun la_cuarentena_desbloquea_el_avance() {
        assertEquals(30L, PushFrontier.advance(0L, listOf(ok(10), ok(20), ok(30))))
        assertEquals(30L, PushFrontier.advance(0L, listOf(ok(10), RowOutcome(20, settled = true), ok(30))))
    }

    @Test fun nunca_retrocede() {
        assertEquals(100L, PushFrontier.advance(100L, listOf(ok(10), ok(20))))
        assertEquals(100L, PushFrontier.advance(100L, listOf(fail(10))))
    }

    @Test fun desordenadas_se_ordenan_solas() {
        assertEquals(10L, PushFrontier.advance(0L, listOf(ok(30), fail(20), ok(10))))
    }
}
