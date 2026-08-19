package com.arkiv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La aritmética del salto incremental y el estado que la acompaña. Vivían dentro de `PlayerContent`
 * —`seekBy` era una función local cerrada sobre cuatro variables del composable—, así que la regla
 * que de verdad importa (acumular sobre el destino anterior y no sobre la posición del player) no
 * se podía ejercitar desde ningún lado.
 */
class PlayerSeekTest {

    // ---- destinoDeSeek ----

    @Test
    fun `suma el delta a la base`() {
        assertEquals(70_000L, destinoDeSeek(base = 60_000L, deltaMs = 10_000L, duracionMs = 600_000L))
    }

    @Test
    fun `retrocede con delta negativo`() {
        assertEquals(50_000L, destinoDeSeek(base = 60_000L, deltaMs = -10_000L, duracionMs = 600_000L))
    }

    @Test
    fun `no se pasa del final`() {
        assertEquals(600_000L, destinoDeSeek(base = 595_000L, deltaMs = 10_000L, duracionMs = 600_000L))
    }

    @Test
    fun `no cae antes del principio`() {
        assertEquals(0L, destinoDeSeek(base = 5_000L, deltaMs = -10_000L, duracionMs = 600_000L))
    }

    /**
     * Duración 0 = "todavía no se sabe" (arranque, o un directo). Ahí no hay tope que respetar, y
     * acotar contra 0 dejaría cualquier salto hacia adelante clavado en el segundo cero.
     */
    @Test
    fun `sin duracion conocida solo se corta por abajo`() {
        assertEquals(70_000L, destinoDeSeek(base = 60_000L, deltaMs = 10_000L, duracionMs = 0L))
        assertEquals(0L, destinoDeSeek(base = 5_000L, deltaMs = -10_000L, duracionMs = 0L))
    }

    // ---- la rafaga ----

    /**
     * El corazón del seek diferido: doce pulsaciones seguidas del D-pad tienen que sumar doce
     * pasos. Si la cuenta partiera de la posición del player —que solo avanza cuando un seek real
     * aterriza, y en Magis un rango puede tardar hasta 20 s— la ráfaga competiría contra sí misma
     * y el resultado dependería de cuántos alcanzaron a completarse.
     */
    @Test
    fun `una rafaga acumula sobre el destino anterior, no sobre el player`() {
        val seek = EstadoDeSeek()
        val posicionDelPlayerQueNoSeMueve = 60_000L
        repeat(12) { seek.saltar(10_000L, posicionDelPlayerQueNoSeMueve, 600_000L) }
        assertEquals(60_000L + 12 * 10_000L, seek.pendienteMs)
    }

    @Test
    fun `el primer salto si parte de la posicion del player`() {
        val seek = EstadoDeSeek()
        seek.saltar(10_000L, posicionActualMs = 60_000L, duracionMs = 600_000L)
        assertEquals(70_000L, seek.pendienteMs)
    }

    @Test
    fun `saltar prende el arrastre y pinta el destino`() {
        val seek = EstadoDeSeek()
        assertFalse(seek.arrastrando)
        seek.saltar(10_000L, 60_000L, 600_000L)
        assertTrue(seek.arrastrando)
        assertEquals(70_000L, seek.posicionAMostrar(posicionRealMs = 60_000L))
    }

    @Test
    fun `confirmar limpia el pendiente y apaga el arrastre`() {
        val seek = EstadoDeSeek()
        seek.saltar(10_000L, 60_000L, 600_000L)
        seek.confirmado()
        assertNull(seek.pendienteMs)
        assertFalse(seek.arrastrando)
    }

    // ---- el arrastre del slider ----

    /**
     * Agarrar la barra tiene que descartar la ráfaga pendiente: si no, el debounce dispararía
     * DESPUÉS de soltar y te devolvería al destino de las flechas, pisando el arrastre.
     */
    @Test
    fun `agarrar la barra descarta la rafaga pendiente`() {
        val seek = EstadoDeSeek()
        seek.saltar(10_000L, 60_000L, 600_000L)
        assertEquals(70_000L, seek.pendienteMs)
        seek.arrastrarA(300_000f)
        assertNull(seek.pendienteMs)
        assertEquals(300_000L, seek.soltar())
    }

    @Test
    fun `soltar apaga el arrastre y devuelve el destino`() {
        val seek = EstadoDeSeek()
        seek.arrastrarA(120_000f)
        assertTrue(seek.arrastrando)
        assertEquals(120_000L, seek.soltar())
        assertFalse(seek.arrastrando)
    }

    @Test
    fun `sin movimiento se muestra la posicion real`() {
        val seek = EstadoDeSeek()
        assertEquals(42_000L, seek.posicionAMostrar(posicionRealMs = 42_000L))
        assertEquals(42_000f, seek.valorDeLaBarra(posicionRealMs = 42_000L), 0.001f)
    }
}
