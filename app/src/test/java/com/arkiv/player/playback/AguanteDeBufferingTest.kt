package com.arkiv.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cuánto se le aguanta a un buffering que no avanza antes de llamarlo error.
 * Ver [AguanteDeBuffering] para el porqué.
 */
class AguanteDeBufferingTest {

    @Test fun un_buffering_normal_no_es_un_error() {
        // Baches de 2 s pasan todo el tiempo en torrent y web: no son un fallo.
        assertFalse(AguanteDeBuffering.hayQueRendirse(2_000))
    }

    @Test fun recien_empezado_no_es_un_error() {
        assertFalse(AguanteDeBuffering.hayQueRendirse(0))
    }

    // ─── el invariante que importa ─────────────────────────────────────────
    // Este es el punto de todo el objeto. Si el reproductor se rindiera antes que la capa de red,
    // mataría justo el caso que PoliticaOrigen existe para salvar: un archive.org que tarda 72 s
    // pero termina contestando. El error tiene que llegar DESPUÉS de que ya no queda esperanza.

    @Test fun no_se_rinde_mientras_la_red_todavia_puede_contestar() {
        assertFalse(
            "rendirse acá cancelaría un origen lento que todavía iba a responder",
            AguanteDeBuffering.hayQueRendirse(AguanteDeBuffering.PRESUPUESTO_RED_MS),
        )
    }

    @Test fun el_limite_siempre_supera_el_presupuesto_de_la_red() {
        // Blindaje contra el futuro: si alguien sube los timeouts de PoliticaOrigen y este límite
        // no lo acompaña, vuelve el bug silenciosamente. Los dos números están atados en el código.
        assertTrue(AguanteDeBuffering.LIMITE_MS > AguanteDeBuffering.PRESUPUESTO_RED_MS)
    }

    @Test fun el_presupuesto_de_red_sale_de_PoliticaOrigen_de_verdad() {
        val esperado = (0 until PoliticaOrigen.INTENTOS).sumOf {
            PoliticaOrigen.respuestaMs(it).toLong() + PoliticaOrigen.esperaMs(it)
        }
        assertTrue(AguanteDeBuffering.PRESUPUESTO_RED_MS == esperado)
    }

    // ─── pero se rinde ─────────────────────────────────────────────────────

    @Test fun pasado_el_limite_es_un_error() {
        assertTrue(AguanteDeBuffering.hayQueRendirse(AguanteDeBuffering.LIMITE_MS + 1))
    }

    @Test fun los_dos_minutos_y_medio_que_se_vieron_colgados_ya_serian_error() {
        // Caso real del 2026-08-10: de 10:38 a 10:40+ en Buffering, sin imagen y sin aviso.
        // No se afirma que 150 s baste (depende del presupuesto), sino que un cuelgue así termina
        // en error en vez de quedarse girando para siempre.
        assertTrue(AguanteDeBuffering.hayQueRendirse(AguanteDeBuffering.LIMITE_MS * 2))
    }

    @Test fun el_limite_no_es_absurdamente_largo() {
        // Nadie mira una ruedita cinco minutos. Es el techo de lo tolerable.
        assertTrue("límite = ${AguanteDeBuffering.LIMITE_MS}ms", AguanteDeBuffering.LIMITE_MS <= 300_000)
    }
}
