package com.arkiv.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoAvanceTest {

    private val VEINTICUATRO_MIN = 24 * 60_000L + 39_000L

    /** El caso normal: el capítulo llegó al final y sigue el siguiente. */
    @Test fun fin_al_final_del_capitulo_avanza() {
        assertTrue(AutoAvance.esFinDeCapitulo(positionMs = VEINTICUATRO_MIN, durationMs = VEINTICUATRO_MIN))
    }

    /** Los créditos suelen dejar la posición unos segundos corta del total, y VLC no siempre llega
     * al último frame: el final es una franja, no un número exacto. */
    @Test fun fin_en_los_ultimos_segundos_avanza() {
        assertTrue(AutoAvance.esFinDeCapitulo(positionMs = VEINTICUATRO_MIN - 30_000, durationMs = VEINTICUATRO_MIN))
    }

    /**
     * The reason this rule exists: VLC emits the SAME EndReached when the stream drops (Magis's
     * CDN going slow, or -- for a source removed in this branch's pruning -- a torrent running out
     * of peers). Without this guard, a network hiccup at minute 3 didn't pause: it skipped to the
     * next chapter, which could stall the same way, cascading through the whole series.
     */
    @Test fun corte_a_mitad_del_capitulo_no_avanza() {
        assertFalse(AutoAvance.esFinDeCapitulo(positionMs = 3 * 60_000, durationMs = VEINTICUATRO_MIN))
    }

    /** Un corte apenas arranca es lo más parecido a un fallo de la fuente, nunca un fin. */
    @Test fun corte_al_arrancar_no_avanza() {
        assertFalse(AutoAvance.esFinDeCapitulo(positionMs = 0, durationMs = VEINTICUATRO_MIN))
    }

    /**
     * Sin duración conocida no hay contra qué comparar (pasa en magis cuando la sonda de duración
     * pierde contra el CDN). Se avanza igual —es lo que el usuario espera— pero exigiendo que haya
     * sonado un rato, que es lo único que distingue un fin de un fallo al abrir.
     */
    @Test fun sin_duracion_avanza_si_sono_un_rato() {
        assertTrue(AutoAvance.esFinDeCapitulo(positionMs = 12 * 60_000, durationMs = 0))
    }

    @Test fun sin_duracion_no_avanza_si_acaba_de_arrancar() {
        assertFalse(AutoAvance.esFinDeCapitulo(positionMs = 4_000, durationMs = 0))
    }
}
