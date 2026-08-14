package com.arkiv.player.playback

import org.junit.Test

/**
 * Si entre dos sondeos el reloj del reproductor avanzó DE VERDAD.
 *
 * Existe por un bug que se metió el 2026-08-14 al sacar el aviso de "⏱ CORRIENDO" de adentro del
 * bloque de fin-de-pausa: el watcher guarda la última posición vista en `-1` mientras no observó
 * ninguna, así que la PRIMERA lectura —con el reloj todavía en 0— daba `0 != -1` y se leía como
 * "avanzó". Medido en el Fire TV, las seis reproducciones de esa tanda reportaron:
 *
 * ```
 * 10:22:01.398  ⏱ CORRIENDO a los 439ms de loadMedia (primera imagen a los -1ms)
 * 10:22:03.198  ⏱ abrió en 2239ms (loadMedia → primera imagen)
 * ```
 *
 * O sea el video "corriendo" 1,8 s ANTES de tener imagen, que es imposible. El número quedaba
 * inservible justo en la métrica que se había tocado para poder comparar arranques.
 */
class RelojDeReproduccionTest {

    /** `-1` es "todavía no observé ninguna posición", no una posición. */
    @Test fun `la primera lectura nunca cuenta como avance`() {
        assert(!RelojDeReproduccion.avanzoDeVerdad(anterior = -1L, ahora = 0L))
        assert(!RelojDeReproduccion.avanzoDeVerdad(anterior = -1L, ahora = 5_000L))
    }

    @Test fun `el reloj quieto no es avance`() {
        assert(!RelojDeReproduccion.avanzoDeVerdad(anterior = 0L, ahora = 0L))
        assert(!RelojDeReproduccion.avanzoDeVerdad(anterior = 5_000L, ahora = 5_000L))
    }

    @Test fun `moverse hacia adelante si es avance`() {
        assert(RelojDeReproduccion.avanzoDeVerdad(anterior = 0L, ahora = 40L))
        assert(RelojDeReproduccion.avanzoDeVerdad(anterior = 5_000L, ahora = 5_500L))
    }

    /**
     * Retroceder es un SALTO hacia atrás, no el arranque. Contarlo como "el video empezó a caminar"
     * pondría el cronómetro del arranque en un momento que no tiene nada que ver.
     */
    @Test fun `retroceder no cuenta como avance`() {
        assert(!RelojDeReproduccion.avanzoDeVerdad(anterior = 5_000L, ahora = 0L))
        assert(!RelojDeReproduccion.avanzoDeVerdad(anterior = 5_000L, ahora = 4_000L))
    }
}
