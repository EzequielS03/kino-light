package com.arkiv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Qué pasa con lo que suena cuando la persona se va de la app. */
class PausaAlSalirTest {

    @Test fun `en el televisor se pausa todo, VLC incluido`() {
        assertTrue(hayQuePausarAlSalir(esTv = true, esExoPlayer = true, casteando = false))
        assertTrue(hayQuePausarAlSalir(esTv = true, esExoPlayer = false, casteando = false))
    }

    /** Magis, el vivo y Caracol no tienen cómo pararlos desde afuera. */
    @Test fun `en el celular se pausa un ExoPlayer`() {
        assertTrue(hayQuePausarAlSalir(esTv = false, esExoPlayer = true, casteando = false))
    }

    /** Un descargado suena en `PlaybackService`: en el celular sigue, a propósito. */
    @Test fun `en el celular VLC sigue sonando`() {
        assertFalse(hayQuePausarAlSalir(esTv = false, esExoPlayer = false, casteando = false))
    }

    @Test fun `enviando a un Chromecast no se pausa`() {
        for (tv in listOf(true, false)) for (exo in listOf(true, false)) {
            assertFalse("tv=$tv exo=$exo", hayQuePausarAlSalir(esTv = tv, esExoPlayer = exo, casteando = true))
        }
    }

    @Test fun `un canal en vivo en ExoPlayer se detiene y un video se pausa`() {
        assertEquals(AlIrseAlFondo.DETENER_EL_DIRECTO, alIrseAlFondo(esTv = true, esExoPlayer = true, casteando = false, enVivo = true))
        assertEquals(AlIrseAlFondo.DETENER_EL_DIRECTO, alIrseAlFondo(esTv = false, esExoPlayer = true, casteando = false, enVivo = true))
        assertEquals(AlIrseAlFondo.PAUSAR, alIrseAlFondo(esTv = true, esExoPlayer = true, casteando = false, enVivo = false))
        assertEquals(AlIrseAlFondo.PAUSAR, alIrseAlFondo(esTv = false, esExoPlayer = true, casteando = false, enVivo = false))
        assertEquals(AlIrseAlFondo.PAUSAR, alIrseAlFondo(esTv = true, esExoPlayer = false, casteando = false, enVivo = false))
        assertEquals(AlIrseAlFondo.SEGUIR, alIrseAlFondo(esTv = false, esExoPlayer = false, casteando = false, enVivo = false))
        assertEquals(AlIrseAlFondo.SEGUIR, alIrseAlFondo(esTv = true, esExoPlayer = true, casteando = true, enVivo = true))
    }
}
