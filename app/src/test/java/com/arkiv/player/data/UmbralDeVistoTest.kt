package com.arkiv.player.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cuándo un capítulo cuenta como visto.
 *
 * El bug real (reportado en device, 2026-08-11): la regla era `positionMs >= duracion * 0.6`, y
 * "Continuar viendo" excluye lo visto. O sea que en el minuto 15 de un capítulo de 24 el capítulo
 * DESAPARECÍA del home mientras se lo estaba mirando, con nueve minutos por delante. Y como
 * `ItemDetail.resumeEpisode` ofrece el SIGUIENTE capítulo cuando el actual está visto, el detalle
 * pasaba a proponer otro capítulo empezando de cero — se sentía como haber perdido el avance.
 *
 * La regla nueva es "cuánto FALTA para el final", no "qué fracción llevo": tres minutos significan
 * lo mismo en un capítulo de 24 minutos que en una película de dos horas, y un porcentaje no.
 */
class UmbralDeVistoTest {

    private val min = 60_000L

    /**
     * El caso exacto que se midió en el TV: Dragon Ball E125, 1479 s de duración, mirándose en el
     * segundo 1119 (75,7%). Con la regla vieja YA estaba marcado visto y fuera del home.
     */
    @Test
    fun `el caso real del reporte ya no cuenta como visto`() {
        assertFalse(UmbralDeVisto.yaLoViste(positionMs = 1_119_000, durationMs = 1_479_000))
    }

    @Test
    fun `un capitulo de 24 minutos no esta visto a los 15`() {
        assertFalse(UmbralDeVisto.yaLoViste(positionMs = 15 * min, durationMs = 24 * min))
    }

    /** Ya empezó el ending: cuenta como visto y recién ahí se ofrece el siguiente. */
    @Test
    fun `un capitulo de 24 minutos si esta visto a los 22 y medio`() {
        assertTrue(UmbralDeVisto.yaLoViste(positionMs = 22 * min + 30_000, durationMs = 24 * min))
    }

    /**
     * Donde el porcentaje fallaba: al 90% de una película de dos horas todavía faltan 12 minutos,
     * que es media resolución. Por eso manda "cuánto falta" y no la fracción.
     */
    @Test
    fun `una pelicula de dos horas no esta vista al 90 por ciento`() {
        assertFalse(UmbralDeVisto.yaLoViste(positionMs = 108 * min, durationMs = 120 * min))
    }

    @Test
    fun `una pelicula de dos horas si esta vista cuando faltan menos de tres minutos`() {
        assertTrue(UmbralDeVisto.yaLoViste(positionMs = 118 * min, durationMs = 120 * min))
    }

    /**
     * Sin el piso porcentual, cualquier cosa que dure menos de tres minutos nacería vista: la
     * resta daría negativo y la posición 0 ya lo superaría.
     */
    @Test
    fun `un clip de dos minutos no nace visto`() {
        assertFalse(UmbralDeVisto.yaLoViste(positionMs = 0, durationMs = 2 * min))
    }

    @Test
    fun `un clip de dos minutos si esta visto casi al final`() {
        assertTrue(UmbralDeVisto.yaLoViste(positionMs = 115_000, durationMs = 2 * min))
    }

    /** Duración desconocida (Magis tarda en resolverla): nunca se marca visto a ciegas. */
    @Test
    fun `sin duracion no se marca visto`() {
        assertFalse(UmbralDeVisto.yaLoViste(positionMs = 5 * min, durationMs = 0))
    }

    @Test
    fun `llegar al final cuenta como visto`() {
        assertTrue(UmbralDeVisto.yaLoViste(positionMs = 24 * min, durationMs = 24 * min))
    }
}
