package com.arkiv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Qué pasa con lo que suena cuando la persona se va de la app. */
class PausaAlSalirTest {

    @Test fun `en el televisor se pausa todo, el local incluido`() {
        assertTrue(hayQuePausarAlSalir(esTv = true, esExoPlayer = true, casteando = false))
        assertTrue(hayQuePausarAlSalir(esTv = true, esExoPlayer = false, casteando = false))
    }

    /** Magis, el vivo y Caracol no tienen cómo pararlos desde afuera. */
    @Test fun `en el celular se pausa un ExoPlayer`() {
        assertTrue(hayQuePausarAlSalir(esTv = false, esExoPlayer = true, casteando = false))
    }

    /**
     * A downloaded file plays on the ExoPlayer hosted by `PlaybackService`, reached through the
     * screen's `controller`, so `esExoPlayer` is false for it: on the phone it keeps playing in the
     * background, with the media notification. This is the rule that keeps that working.
     */
    @Test fun `on the phone the service-hosted local player keeps playing`() {
        assertFalse(hayQuePausarAlSalir(esTv = false, esExoPlayer = false, casteando = false))
        assertEquals(
            AlIrseAlFondo.SEGUIR,
            alIrseAlFondo(esTv = false, esExoPlayer = false, casteando = false, enVivo = false),
        )
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

    /** Un canal que la persona había pausado no puede arrancar solo al volver. */
    @Test fun `al volver, un directo que sonaba vuelve a sonar y uno en pausa sigue en pausa`() {
        assertEquals(AlVolverAlDirecto.REANUDAR_EN_EL_DIRECTO, alVolverAlDirecto(sonabaAlSalir = true))
        assertEquals(AlVolverAlDirecto.SEGUIR_EN_PAUSA, alVolverAlDirecto(sonabaAlSalir = false))
    }
}
