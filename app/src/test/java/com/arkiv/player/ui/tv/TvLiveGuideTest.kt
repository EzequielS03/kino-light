package com.arkiv.player.ui.tv

import com.arkiv.player.data.gateway.LiveProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvLiveGuideTest {
    @Test
    fun `media hora mide la mitad que una hora`() {
        val hora = LiveProgram("h", 0, 3600, "")
        val media = LiveProgram("m", 0, 1800, "")
        // Comparar solo la PROPORCIÓN (anchoDp(hora)/2 contra anchoDp(media)) no alcanza: un error
        // de escala que afecte a los dos por igual -- p. ej. olvidarse el /3600f dentro de anchoDp,
        // o bajar DP_POR_HORA de 300f a 150f -- deja esta cuenta en verde igual (hallazgo de
        // revisión, confirmado con mutación). Por eso el valor esperado va LITERAL (300f), no como
        // `DP_POR_HORA`: si comparara contra el propio símbolo, una mutación que cambie la
        // constante movería los dos lados de la comparación igual y seguiría sin detectarse. 300
        // dp/h es la cifra que el brief fija a propósito ("deja ver ~4 h en una pantalla de TV de
        // 1280 dp"): clavarla acá hace que cambiarla sea una decisión deliberada, no un accidente
        // silencioso.
        assertEquals(300f, anchoDp(hora), 0.01f)
        assertEquals(150f, anchoDp(media), 0.01f)
    }

    @Test
    fun `un programa mas corto que el minimo igual se puede enfocar`() {
        assertTrue(anchoDp(LiveProgram("corto", 0, 30, "")) >= 40f)
    }

    @Test
    fun `la ventana arranca en la hora en punto anterior`() {
        // instante = 2026-08-11 14:23:45 UTC (1786458225). La hora en punto anterior es
        // 2026-08-11 14:00:00 UTC (1786456800) -- verificado con
        // `date -u -r 1786456800` antes de escribir el test, no copiado del brief: la
        // constante 1786230000 que trae el brief corresponde a otro instante (2026-08-08
        // 23:00:00 UTC), y usarla sin recalcular hubiera probado una resta que no tiene
        // nada que ver con el `instante` de este test.
        assertEquals(1786456800L, ventanaDe(1786458225L).first)
    }
}
