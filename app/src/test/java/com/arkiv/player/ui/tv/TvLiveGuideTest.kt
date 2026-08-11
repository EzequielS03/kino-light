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
        assertEquals(anchoDp(hora) / 2, anchoDp(media), 0.01f)
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
