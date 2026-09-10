package com.arkiv.player.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Que Caracol arranque con la primera imagen: ni antes (el audio sonando bajo el spinner) ni nunca
 * (mudo y colgado si la imagen no llega).
 */
class ArranqueConLaPrimeraImagenTest {

    private val espera = 10_000L
    private val t0 = 1_000L

    private fun preparado() = ArranqueConLaPrimeraImagen(esperaMaximaMs = espera).apply { empezo(t0) }

    @Test fun `preparado y sin imagen, no arranca antes de tiempo`() {
        val a = preparado()
        assertTrue(a.esperando)
        assertFalse(a.vencio(t0))
        assertFalse(a.vencio(t0 + espera - 1))
        assertTrue(a.esperando)
    }

    @Test fun `la primera imagen lo arranca, una sola vez`() {
        val a = preparado()
        assertTrue(a.llegoLaImagen())
        assertFalse(a.esperando)
        // Otra primera imagen (tras un seek o un re-preparado) no le vuelve a dar play.
        assertFalse(a.llegoLaImagen())
        assertFalse(a.vencio(t0 + espera * 3))
    }

    @Test fun `sin imagen, la espera vence y arranca igual`() {
        val a = preparado()
        assertTrue(a.vencio(t0 + espera))
        assertFalse(a.esperando)
        assertFalse(a.vencio(t0 + espera + 500))
        // La imagen que llega tarde tampoco le da play otra vez.
        assertFalse(a.llegoLaImagen())
    }

    /** Una pausa (o un play) de la persona no se confunde con la espera. */
    @Test fun `si la persona decidio mientras esperaba, ni la imagen ni el vencimiento la pisan`() {
        val a = preparado()
        a.laPersonaDecidio()
        assertFalse(a.esperando)
        assertFalse(a.llegoLaImagen())
        assertFalse(a.vencio(t0 + espera * 3))
    }

    /** Irse de la app esperando la imagen: ni la salida de seguridad ni la imagen al volver le dan play. */
    @Test fun `si la app se fue al fondo esperando, no arranca sola`() {
        val a = preparado()
        a.cancelar()
        assertFalse(a.esperando)
        assertFalse(a.vencio(t0 + espera * 3))
        assertFalse(a.llegoLaImagen())
    }

    @Test fun `antes de preparar no hay espera`() {
        val a = ArranqueConLaPrimeraImagen(esperaMaximaMs = espera)
        assertFalse(a.esperando)
        assertFalse(a.vencio(t0 + espera * 3))
        assertFalse(a.llegoLaImagen())
    }

    /** Sin techo, un aparato que no pinte en pausa dejaría el video mudo y colgado para siempre. */
    @Test fun `la espera por defecto tiene techo`() {
        val a = ArranqueConLaPrimeraImagen().apply { empezo(0L) }
        assertTrue(a.vencio(ESPERA_MAXIMA_DE_LA_PRIMERA_IMAGEN_MS))
    }
}
