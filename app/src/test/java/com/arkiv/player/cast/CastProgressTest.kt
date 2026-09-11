package com.arkiv.player.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Qué progreso corresponde guardar mientras se castea, y qué mostrar en la barra.
 *
 * Sin transcodificador el receptor cuenta la posición y la duración del mismo archivo que el celu;
 * lo único que sigue haciendo falta es acotar `C.TIME_UNSET` (un negativo grande, lo que manda un
 * directo en vivo que no sabe su duración) a "no sé" (0).
 */
class CastProgressTest {

    @Test
    fun `casteo directo guarda lo que reporta el receptor`() {
        val p = CastProgress.toSave(reportedPosMs = 120_000, reportedDurMs = 300_000)!!
        assertEquals(120_000, p.positionMs)
        assertEquals(300_000, p.durationMs)
    }

    @Test
    fun `sin duracion no se guarda nada`() {
        // Un directo en vivo: el receptor no sabe la duración.
        assertNull(CastProgress.toSave(reportedPosMs = 1_000, reportedDurMs = 0))
    }

    @Test
    fun `una posicion pasada del final no se guarda`() {
        // Al terminar, el receptor puede reportar de más; guardarlo dejaría el capítulo "sin ver".
        assertNull(CastProgress.toSave(reportedPosMs = 400_000, reportedDurMs = 300_000))
    }

    @Test
    fun `una posicion negativa no se guarda`() {
        assertNull(CastProgress.toSave(reportedPosMs = -1, reportedDurMs = 300_000))
    }

    @Test
    fun `la posicion que se MUESTRA es la del receptor`() {
        // Para la barra de progreso, no para guardar: acá no hay "null", siempre hay que mostrar algo.
        assertEquals(120_000, CastProgress.contentPosition(receiverPosMs = 120_000))
    }

    @Test
    fun `una posicion invalida del receptor no se muestra negativa`() {
        assertEquals(0, CastProgress.contentPosition(receiverPosMs = Long.MIN_VALUE + 1))
    }

    @Test
    fun `la duracion que se MUESTRA es la del receptor`() {
        assertEquals(300_000, CastProgress.contentDuration(receiverDurMs = 300_000))
    }

    @Test
    fun `una duracion invalida del receptor no se muestra`() {
        // TIME_UNSET (directo en vivo sin duración conocida): 0 significa "no sé", y la barra ya
        // sabe manejarlo.
        assertEquals(0, CastProgress.contentDuration(receiverDurMs = Long.MIN_VALUE + 1))
    }
}
