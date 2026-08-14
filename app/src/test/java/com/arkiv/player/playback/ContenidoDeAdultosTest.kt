package com.arkiv.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Qué contenido NO se anota en el historial.
 *
 * El 2026-08-14 dos canales +18 aparecieron en la fila "Canales en vivo" de la pantalla principal,
 * con nombre y logo. Y no alcanzó con borrarlos del aparato: `live_recents` se sincroniza, así que
 * ya habían viajado a PocketBase y podían llegar al celular y a la otra TV. Hubo que limpiarlos en
 * las dos puntas.
 *
 * De ahí la regla: **no se escribe**, en vez de filtrarse al leer. Lo que no se escribe no se puede
 * escapar por una pantalla que nos olvidamos —"seguir viendo" se pinta en el inicio del televisor,
 * en el del celular y en la biblioteca— ni se sube a la nube.
 *
 * Vive acá afuera y no dentro de `saveProgress` para poder fijar sus bordes: el modo de fallar en
 * la otra dirección —dejar de guardar el progreso de contenido normal— es igual de malo y mucho
 * más silencioso.
 */
class ContenidoDeAdultosTest {

    @Test fun `el contenido de adultos no se anota`() {
        assertFalse(ContenidoDeAdultos.hayQueAnotar(esAdulto = true))
    }

    @Test fun `el contenido normal se anota como siempre`() {
        assertTrue(ContenidoDeAdultos.hayQueAnotar(esAdulto = false))
    }

    /**
     * EL BORDE PELIGROSO, y va en esta dirección a propósito: lo que NO se sabe se anota. Un
     * `null` es "no tengo el dato", y tratarlo como adulto dejaría de guardar el progreso de
     * películas normales sin que nadie se entere — un daño silencioso y difícil de rastrear.
     * El riesgo opuesto ya está cubierto por otro lado: al contenido de adultos solo se llega por
     * una sección que no existe sin el código del aparato.
     */
    @Test fun `sin dato, se anota`() {
        assertTrue(ContenidoDeAdultos.hayQueAnotar(esAdulto = null))
    }
}
