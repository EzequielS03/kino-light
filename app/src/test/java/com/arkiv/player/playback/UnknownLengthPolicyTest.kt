package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Qué hacer cuando libVLC no sabe cuánto dura lo que está reproduciendo (TS servido por HTTP).
 * Ver [TsDurationProbe] para el porqué.
 */
class UnknownLengthPolicyTest {

    @Test fun la_duracion_de_vlc_manda_cuando_la_sabe() {
        assertEquals(7_200_000L, UnknownLengthPolicy.effectiveDurationMs(7_200_000L, 999L))
    }

    @Test fun cae_a_la_sondeada_cuando_vlc_no_sabe() {
        assertEquals(10_143_841L, UnknownLengthPolicy.effectiveDurationMs(0L, 10_143_841L))
    }

    @Test fun cero_cuando_no_la_sabe_nadie() {
        assertEquals(0L, UnknownLengthPolicy.effectiveDurationMs(0L, 0L))
    }

    @Test fun sin_fraccion_cuando_vlc_conoce_la_duracion() {
        // Con duración, buscar por tiempo funciona y es exacto: no hay que tocar nada.
        assertNull(UnknownLengthPolicy.seekFraction(60_000L, lengthMs = 7_200_000L, knownDurationMs = 7_200_000L))
    }

    @Test fun sin_fraccion_cuando_tampoco_hay_duracion_sondeada() {
        // Sin ningún total no se puede calcular la fracción; que lo intente por tiempo.
        assertNull(UnknownLengthPolicy.seekFraction(60_000L, lengthMs = 0L, knownDurationMs = 0L))
    }

    @Test fun fraccion_sobre_la_duracion_sondeada() {
        val f = UnknownLengthPolicy.seekFraction(5_000_000L, lengthMs = 0L, knownDurationMs = 10_000_000L)!!
        assertEquals(0.5f, f, 0.0001f)
    }

    @Test fun la_fraccion_no_se_sale_del_rango() {
        assertEquals(1f, UnknownLengthPolicy.seekFraction(99_000_000L, 0L, 10_000_000L)!!, 0.0001f)
        assertEquals(0f, UnknownLengthPolicy.seekFraction(-5_000L, 0L, 10_000_000L)!!, 0.0001f)
    }
}
