package com.arkiv.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los bordes del spinner de "todavía no hay primera imagen".
 *
 * El caso bueno (tapar el negro con sonido) es fácil; lo que hay que fijar por test son los dos
 * modos de fallar, que son peores que el problema original: dejar el spinner encima de un video que
 * sí reproduce, y dejarlo para siempre en algo que nunca va a tener imagen.
 */
class EsperaDePrimeraImagenTest {

    @Test
    fun `recien cargado y sin pistas todavia, se espera`() {
        // El instante exacto que esto vino a tapar: libVLC abriendo, todas las cuentas en 0 —que no
        // es "no hay video", es "todavía no sé"— y la pantalla en negro.
        assertTrue(
            EsperaDePrimeraImagen.hayQueEsperar(
                cargadoHaceMs = 300, huboImagen = false, hayVideoAhora = false,
                pistasDeVideo = 0, pistasDeAudio = 0,
            ),
        )
    }

    @Test
    fun `con audio sonando y video declarado pero sin imagen, se espera`() {
        // El negro CON SONIDO propiamente dicho: ya hay pistas, el audio salió, la imagen no.
        assertTrue(
            EsperaDePrimeraImagen.hayQueEsperar(
                cargadoHaceMs = 2_000, huboImagen = false, hayVideoAhora = false,
                pistasDeVideo = 2, pistasDeAudio = 3,
            ),
        )
    }

    @Test
    fun `contenido de solo audio no espera ninguna imagen`() {
        // Si esperara, el spinner se quedaría puesto para siempre encima de algo que suena bien.
        assertFalse(
            EsperaDePrimeraImagen.hayQueEsperar(
                cargadoHaceMs = 2_000, huboImagen = false, hayVideoAhora = false,
                pistasDeVideo = 0, pistasDeAudio = 2,
            ),
        )
    }

    @Test
    fun `en cuanto hay imagen deja de esperar`() {
        assertFalse(
            EsperaDePrimeraImagen.hayQueEsperar(
                cargadoHaceMs = 2_000, huboImagen = false, hayVideoAhora = true,
                pistasDeVideo = 2, pistasDeAudio = 3,
            ),
        )
    }

    @Test
    fun `una imagen previa manda, aunque ahora no haya salida de video`() {
        // Perder la salida DESPUÉS de haber tenido imagen es el otro caso, y lo cubre
        // `esperandoVideo` en PlayerScreen (volver del segundo plano). Este no se mete ahí.
        assertFalse(
            EsperaDePrimeraImagen.hayQueEsperar(
                cargadoHaceMs = 2_000, huboImagen = true, hayVideoAhora = false,
                pistasDeVideo = 2, pistasDeAudio = 3,
            ),
        )
    }

    @Test
    fun `pasado el tope se muestra lo que haya`() {
        assertFalse(
            EsperaDePrimeraImagen.hayQueEsperar(
                cargadoHaceMs = EsperaDePrimeraImagen.TOPE_MS + 1, huboImagen = false,
                hayVideoAhora = false, pistasDeVideo = 2, pistasDeAudio = 3,
            ),
        )
    }

    @Test
    fun `el tope aguanta mas que el rescate a software`() {
        // El rescate "hardware sin imagen → software" tarda 12 s en dispararse y la recarga en
        // software es la que termina dando imagen. Un tope más corto que eso sacaría el spinner
        // justo en el peor momento: negro pelado durante el rescate.
        assertTrue(EsperaDePrimeraImagen.TOPE_MS > 12_000L * 2)
    }

    @Test
    fun `sin media cargado no hay nada que esperar`() {
        assertFalse(
            EsperaDePrimeraImagen.hayQueEsperar(
                cargadoHaceMs = -1, huboImagen = false, hayVideoAhora = false,
                pistasDeVideo = 0, pistasDeAudio = 0,
            ),
        )
    }
}
