package com.arkiv.player.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los dos viajes al CDN que hay que hacer ANTES de abrir el video. Ver [ArranqueDeMagis].
 */
class ArranqueDeMagisTest {

    @Test fun devuelve_la_duracion_que_dio_la_sonda() = runTest {
        val ms = ArranqueDeMagis.duracionYArranque(sonda = { 1_430_637L }, precalentar = {})
        assertEquals(1_430_637L, ms)
    }

    @Test fun la_sonda_y_el_precalentado_corren_a_la_vez() = runTest {
        // El test que justifica que esto exista. Cada uno espera a que el OTRO haya arrancado: si
        // corrieran en serie, el primero se quedaría esperando para siempre una señal que el
        // segundo todavía no puede mandar, y esto vence por timeout.
        val sondaArranco = CompletableDeferred<Unit>()
        val precalentadoArranco = CompletableDeferred<Unit>()
        val ms = withTimeout(5_000) {
            ArranqueDeMagis.duracionYArranque(
                sonda = { sondaArranco.complete(Unit); precalentadoArranco.await(); 99L },
                precalentar = { precalentadoArranco.complete(Unit); sondaArranco.await() },
            )
        }
        assertEquals(99L, ms)
    }

    @Test fun no_vuelve_hasta_que_el_precalentado_termino() = runTest {
        // El precalentado tiene que estar EN LA MANO antes de que el reproductor abra la URL: esa
        // es toda su razón de ser (ver ArchiveCacheProxy.precalentar). Adelantarse a publicar lo
        // volvería decorativo.
        var precalentadoListo = false
        ArranqueDeMagis.duracionYArranque(
            sonda = { 100L },
            precalentar = { precalentadoListo = true },
        )
        assertTrue("volvió antes de que el precalentado terminara", precalentadoListo)
    }

    @Test fun un_precalentado_que_falla_no_impide_reproducir() = runTest {
        // Best-effort: sin arranque caliente se reproduce igual, solo sin la garantía.
        val ms = ArranqueDeMagis.duracionYArranque(
            sonda = { 500L },
            precalentar = { error("el origen no dio el arranque") },
        )
        assertEquals(500L, ms)
    }

    @Test fun una_sonda_que_falla_da_cero_y_deja_reproducir() = runTest {
        // Sin duración la barra queda fea, pero la película arranca. Nunca al revés.
        val ms = ArranqueDeMagis.duracionYArranque(
            sonda = { error("los dos tramos vencidos") },
            precalentar = {},
        )
        assertEquals(0L, ms)
    }
}
