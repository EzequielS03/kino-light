package com.arkiv.player.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La condición del spinner. Estaba escrita DOS veces —en el overlay que lo dibuja y en el log que
 * lo diagnostica— así que se podían desincronizar y el log dejaría de describir lo que se ve, que
 * es justo para lo que existe.
 */
class SpinnerDelPlayerTest {

    private fun spinner(
        sinPlaylist: Boolean = false,
        buffereando: Boolean = false,
        sinPrimeraImagen: Boolean = false,
        perdioLaSalidaDeVideo: Boolean = false,
        casting: Boolean = false,
    ) = hayQueMostrarElSpinner(sinPlaylist, buffereando, sinPrimeraImagen, perdioLaSalidaDeVideo, casting)

    @Test
    fun `sin ninguna razon no se muestra`() {
        assertFalse(spinner())
    }

    @Test
    fun `cada razon por si sola lo muestra`() {
        assertTrue(spinner(sinPlaylist = true))
        assertTrue(spinner(buffereando = true))
        assertTrue(spinner(sinPrimeraImagen = true))
        assertTrue(spinner(perdioLaSalidaDeVideo = true))
    }

    /**
     * "Arranca negro y con sonido": libVLC ya suelta el audio pero todavía no dio el primer
     * fotograma, y ahí `playbackState` NO es BUFFERING. Sin esta razón aparte, la pantalla se
     * quedaba sin spinner y sin imagen.
     */
    @Test
    fun `sin primera imagen cuenta aunque no este buffereando`() {
        assertTrue(spinner(buffereando = false, sinPrimeraImagen = true))
    }

    /**
     * Casteando, esperar la salida de video LOCAL no tiene sentido: la imagen la pone la TV, así
     * que esa espera no importa ni va a llegar.
     */
    @Test
    fun `casteando se ignora la perdida de la salida de video`() {
        assertTrue(spinner(perdioLaSalidaDeVideo = true, casting = false))
        assertFalse(spinner(perdioLaSalidaDeVideo = true, casting = true))
    }

    /**
     * Las otras tres SÍ aplican casteando: mientras el receptor carga, la pantalla local también
     * tiene que explicar qué pasa. Sin esto quedaba con el degradado y el cartel de Chromecast y
     * nada más — ni controles ni explicación.
     */
    @Test
    fun `casteando las otras tres razones siguen valiendo`() {
        assertTrue(spinner(sinPlaylist = true, casting = true))
        assertTrue(spinner(buffereando = true, casting = true))
        assertTrue(spinner(sinPrimeraImagen = true, casting = true))
    }
}
