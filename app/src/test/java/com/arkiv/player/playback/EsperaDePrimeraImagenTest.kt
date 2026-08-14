package com.arkiv.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // ---- El salto de la reanudación ----

    /**
     * MEDIDO EN EL FIRE TV el 2026-08-14, reanudando Dragon Ball E139 en 22:07:
     *
     * ```
     * 09:14:50.150  loadMedia start=1327653ms
     * 09:14:50.319  ← pide rango=bytes=0-              ← VLC abre en el BYTE 0
     * 09:14:51.155  ⏱ abrió en 1025ms → primera imagen ← ...del PRINCIPIO del capítulo
     * 09:14:51.448  PAUSA (buffering) en pos=0ms
     * 09:14:52.951  REANUDO tras 1501ms (pos=1327116ms)
     * ```
     *
     * `:start-time` NO abre en el minuto guardado: VLC abre en 0, saca un frame de ahí, y RECIÉN
     * ENTONCES salta. Esa primera imagen prende `huboImagen`, el spinner se apaga — y el usuario se
     * queda 1,5 s mirando un fotograma CONGELADO DEL PRINCIPIO mientras el audio ya suena. Se ve
     * exactamente igual que un cuelgue, y encima muestra contenido equivocado.
     *
     * El reproductor original tapa este mismo hueco: su salto prende el spinner en el instante en
     * que lo pide (`A0` → `buffering/show`, en el decompilado).
     */
    @Test
    fun `una imagen del principio no cancela la espera si falta llegar al punto de reanudacion`() {
        assertTrue(
            EsperaDePrimeraImagen.hayQueEsperar(
                cargadoHaceMs = 1_300, huboImagen = true, hayVideoAhora = true,
                pistasDeVideo = 1, pistasDeAudio = 2,
                pedidoMs = 1_327_653, posicionMs = 0,
            ),
        )
    }

    /** Ya llegó al punto pedido: se acabó la espera, aunque sea por poco margen. */
    @Test
    fun `llegado el punto de reanudacion la espera termina`() {
        assertFalse(
            EsperaDePrimeraImagen.hayQueEsperar(
                cargadoHaceMs = 2_900, huboImagen = true, hayVideoAhora = true,
                pistasDeVideo = 1, pistasDeAudio = 2,
                pedidoMs = 1_327_653, posicionMs = 1_327_116,
            ),
        )
    }

    /** Sin reanudación pedida (arranque desde cero) nada de esto aplica: manda la regla de siempre. */
    @Test
    fun `sin reanudacion pedida la primera imagen sigue cancelando la espera`() {
        assertFalse(
            EsperaDePrimeraImagen.hayQueEsperar(
                cargadoHaceMs = 1_300, huboImagen = true, hayVideoAhora = true,
                pistasDeVideo = 1, pistasDeAudio = 2,
                pedidoMs = 0, posicionMs = 0,
            ),
        )
    }

    /**
     * El tope manda igual. Si el salto nunca llega —el caso que este spinner NO puede arreglar— hay
     * que devolver la pantalla en algún momento en vez de girar para siempre.
     */
    @Test
    fun `el tope corta la espera aunque nunca llegue al punto pedido`() {
        assertFalse(
            EsperaDePrimeraImagen.hayQueEsperar(
                cargadoHaceMs = EsperaDePrimeraImagen.TOPE_MS + 1, huboImagen = true,
                hayVideoAhora = true, pistasDeVideo = 1, pistasDeAudio = 2,
                pedidoMs = 1_327_653, posicionMs = 0,
            ),
        )
    }

    /**
     * Adelantarse un poco cuenta como llegado. El salto cae en el keyframe ANTERIOR al punto pedido,
     * así que exigir `posicion >= pedido` dejaría el spinner puesto sobre un video que ya arrancó
     * bien: en la medición de arriba aterrizó 537 ms antes de lo pedido.
     */
    @Test
    fun `aterrizar un poco antes del punto pedido cuenta como llegado`() {
        assertFalse(
            EsperaDePrimeraImagen.hayQueEsperar(
                cargadoHaceMs = 2_900, huboImagen = true, hayVideoAhora = true,
                pistasDeVideo = 1, pistasDeAudio = 2,
                pedidoMs = 1_327_653, posicionMs = 1_320_000,
            ),
        )
    }
}
