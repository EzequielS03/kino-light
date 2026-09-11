package com.arkiv.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What content is NOT logged to history.
 *
 * On 2026-08-14, two +18 channels showed up in the "Live channels" row on the home screen, with
 * name and logo. Deleting them from the device wasn't enough: `live_recents` synced through
 * PocketBase (removed in this branch's pruning), so they had already traveled there and could
 * reach the phone and the other TV. Both ends had to be cleaned up.
 *
 * Hence the rule: **don't write it**, instead of filtering it out on read. What isn't written
 * can't slip through some screen we forgot to filter —"continue watching" is painted on the TV's
 * home, on the phone's, and in the library— and, back when sync existed, couldn't be uploaded
 * either.
 *
 * Lives out here and not inside `saveProgress` so its edges can be pinned down: failing the other
 * way —no longer saving normal content's progress— is just as bad and much quieter.
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
