package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ─── duración absoluta estando dentro de una ventana ───────────────────
    // avformat SÍ informa duración, pero la del tramo abierto. Si esa ganara, la barra mediría el
    // pedazo y no la película.

    @Test fun dentro_de_una_ventana_manda_la_duracion_conocida() {
        // Caso real: se reanudó en 1h14 de una de 2h06 y VLC informó los 51 min que quedaban.
        assertEquals(
            7_560_000L,
            UnknownLengthPolicy.duracionAbsolutaMs(
                lengthMs = 3_101_234L, knownDurationMs = 7_560_000L, baseOffsetMs = 4_466_227L,
            ),
        )
    }

    @Test fun sin_ventana_se_comporta_como_siempre() {
        assertEquals(
            7_200_000L,
            UnknownLengthPolicy.duracionAbsolutaMs(7_200_000L, knownDurationMs = 999L, baseOffsetMs = 0L),
        )
    }

    @Test fun en_ventana_sin_duracion_conocida_se_reconstruye_sumando_el_desfase() {
        assertEquals(
            7_567_461L,
            UnknownLengthPolicy.duracionAbsolutaMs(
                lengthMs = 3_101_234L, knownDurationMs = 0L, baseOffsetMs = 4_466_227L,
            ),
        )
    }

    @Test fun en_ventana_sin_nada_no_se_inventa_una_duracion() {
        assertEquals(0L, UnknownLengthPolicy.duracionAbsolutaMs(0L, 0L, baseOffsetMs = 4_466_227L))
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

    // ─── cuándo hay que sondear ────────────────────────────────────────────
    // La sonda cuesta dos viajes al CDN de magis antes de que arranque el video, y ese CDN tarda
    // entre 0,2 s y 20 s por rango: si la fuente ya dijo cuánto dura, sondear es puro riesgo.

    @Test fun no_se_sonda_si_la_fuente_ya_dijo_cuanto_dura() {
        assertFalse(UnknownLengthPolicy.hayQueSondear(esTs = true, duracionDeLaFuente = 8_580_000L))
    }

    @Test fun se_sonda_el_ts_cuando_la_fuente_no_sabe() {
        assertTrue(UnknownLengthPolicy.hayQueSondear(esTs = true, duracionDeLaFuente = 0L))
    }

    @Test fun nunca_se_sonda_lo_que_no_es_ts() {
        // mp4 y demás traen la duración en su índice: VLC la saca solo.
        assertFalse(UnknownLengthPolicy.hayQueSondear(esTs = false, duracionDeLaFuente = 0L))
    }
}
