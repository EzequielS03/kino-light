package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cuánto esperar a archive.org y cuándo volver a intentar. Ver [PoliticaOrigen] para el porqué.
 */
class PoliticaOrigenTest {

    // ─── el timeout de lectura crece con cada intento ──────────────────────
    // Medido el 2026-08-10 contra el ítem de Evangelion: 72,3 s hasta el primer byte (206 correcto,
    // solo lento). Con los 20 s fijos de antes, TODOS los intentos morían por timeout.

    @Test fun el_primer_intento_no_espera_eternamente() {
        // El caso común (origen sano) contesta en segundos: si el primer intento ya pagara los 90 s
        // del peor caso, un origen realmente muerto tendría al usuario mirando la nada 4 minutos.
        assertEquals(20_000, PoliticaOrigen.leerMs(0))
    }

    @Test fun cada_reintento_le_da_mas_aire_al_origen() {
        assertTrue(PoliticaOrigen.leerMs(1) > PoliticaOrigen.leerMs(0))
        assertTrue(PoliticaOrigen.leerMs(2) > PoliticaOrigen.leerMs(1))
    }

    @Test fun el_ultimo_intento_cubre_los_72s_que_se_midieron() {
        assertTrue(
            "el último intento tiene que aguantar el peor caso medido (72,3 s)",
            PoliticaOrigen.leerMs(PoliticaOrigen.INTENTOS - 1) > 72_300,
        )
    }

    @Test fun un_intento_de_mas_no_se_pasa_del_tope() {
        // Nadie debería pedir el intento 9, pero si pasa no puede devolver media hora.
        assertEquals(PoliticaOrigen.leerMs(PoliticaOrigen.INTENTOS - 1), PoliticaOrigen.leerMs(9))
    }

    @Test fun conectar_es_corto_porque_el_apreton_de_manos_no_es_lo_lento() {
        // Medido: conexión 4,28 s contra primer byte 72,3 s. Lo que tarda es el nodo sirviendo.
        assertTrue(PoliticaOrigen.CONECTAR_MS <= 20_000)
    }

    // ─── la espera entre intentos crece ────────────────────────────────────
    // Antes eran 400 ms fijos: contra un origen saturado, tres intentos en 1,2 s son tres golpes
    // seguidos al mismo nodo que ya está diciendo que no da abasto.

    @Test fun la_espera_entre_intentos_crece() {
        assertTrue(PoliticaOrigen.esperaMs(1) > PoliticaOrigen.esperaMs(0))
        assertTrue(PoliticaOrigen.esperaMs(2) > PoliticaOrigen.esperaMs(1))
    }

    @Test fun la_primera_espera_sigue_siendo_corta() {
        // Un no esporádico se recupera enseguida; no hay que castigar el caso bueno.
        assertEquals(400L, PoliticaOrigen.esperaMs(0))
    }

    // ─── qué vale la pena reintentar ───────────────────────────────────────
    // Esta es la distinción que no existía: el código trataba "me rechazó" y "no contestó a tiempo"
    // como lo mismo.

    @Test fun un_404_no_se_reintenta_nunca() {
        // El archivo no está: reintentar es perder 3 timeouts para llegar al mismo 404. Además es
        // la señal de que archive renombró y hay que revalidar la metadata.
        assertFalse(PoliticaOrigen.valeReintentar(404))
    }

    @Test fun un_503_se_reintenta() {
        assertTrue(PoliticaOrigen.valeReintentar(503))
    }

    @Test fun un_timeout_se_reintenta() {
        // -1 es el código que pone el proxy cuando la conexión murió sin respuesta.
        assertTrue(PoliticaOrigen.valeReintentar(-1))
    }

    @Test fun los_otros_errores_de_servidor_se_reintentan() {
        listOf(429, 500, 502, 504).forEach {
            assertTrue("$it debería reintentarse", PoliticaOrigen.valeReintentar(it))
        }
    }

    @Test fun un_exito_no_se_reintenta() {
        assertFalse(PoliticaOrigen.valeReintentar(200))
        assertFalse(PoliticaOrigen.valeReintentar(206))
    }

    @Test fun un_410_tampoco_se_reintenta() {
        // Igual que el 404: el recurso no vuelve por insistir.
        assertFalse(PoliticaOrigen.valeReintentar(410))
    }

    // ─── el presupuesto total no puede explotar ────────────────────────────

    @Test fun el_peor_caso_completo_no_pasa_de_tres_minutos() {
        // Un origen muerto tiene que rendirse en un tiempo que un humano tolere mirando la pantalla.
        val total = (0 until PoliticaOrigen.INTENTOS).sumOf {
            PoliticaOrigen.leerMs(it).toLong() + PoliticaOrigen.esperaMs(it)
        }
        assertTrue("presupuesto total = ${total}ms", total <= 180_000)
    }
}
