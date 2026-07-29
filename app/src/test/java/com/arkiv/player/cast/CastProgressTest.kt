package com.arkiv.player.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Qué progreso corresponde guardar mientras se castea.
 *
 * Con el stream transcodificado el receptor NO reporta la posición real: como el stream ya arranca
 * en el punto pedido, para él siempre empieza en cero, y además al ser "en vivo" no sabe la
 * duración. Guardar eso tal cual pisaría el minuto bueno con un cero.
 */
class CastProgressTest {

    @Test
    fun `casteo directo guarda lo que reporta el receptor`() {
        val p = CastProgress.toSave(reportedPosMs = 120_000, reportedDurMs = 300_000, baseOffsetMs = 0, knownDurationMs = 0)!!
        assertEquals(120_000, p.positionMs)
        assertEquals(300_000, p.durationMs)
    }

    @Test
    fun `transcodificado suma el punto donde arranco el stream`() {
        // Empezaste a castear en el minuto 10 y el receptor lleva 2 minutos: vas por el 12.
        val p = CastProgress.toSave(reportedPosMs = 120_000, reportedDurMs = 0, baseOffsetMs = 600_000, knownDurationMs = 5_400_000)!!
        assertEquals(720_000, p.positionMs)
    }

    @Test
    fun `transcodificado usa la duracion que ya conocemos`() {
        // El receptor no la sabe (stream en vivo), pero el celu sí: la leyó del archivo.
        val p = CastProgress.toSave(reportedPosMs = 1_000, reportedDurMs = 0, baseOffsetMs = 0, knownDurationMs = 5_400_000)!!
        assertEquals(5_400_000, p.durationMs)
    }

    @Test
    fun `sin duracion por ningun lado no se guarda nada`() {
        // Es el caso que antes hacía que el progreso del casteo transcodificado se perdiera entero.
        assertNull(CastProgress.toSave(reportedPosMs = 1_000, reportedDurMs = 0, baseOffsetMs = 0, knownDurationMs = 0))
    }

    @Test
    fun `una posicion pasada del final no se guarda`() {
        // Al terminar, el receptor puede reportar de más; guardarlo dejaría el capítulo "sin ver".
        assertNull(CastProgress.toSave(reportedPosMs = 400_000, reportedDurMs = 300_000, baseOffsetMs = 0, knownDurationMs = 0))
    }

    @Test
    fun `la posicion que se MUESTRA suma el desfase`() {
        // Para la barra de progreso, no para guardar: acá no hay "null", siempre hay que mostrar algo.
        assertEquals(720_000, CastProgress.contentPosition(receiverPosMs = 120_000, baseOffsetMs = 600_000))
    }

    @Test
    fun `la duracion que se MUESTRA prefiere la que conocemos`() {
        // El receptor manda TIME_UNSET (negativo) con el stream en vivo; la barra tiene que usar la
        // duración real del archivo o queda vacía.
        assertEquals(5_400_000, CastProgress.contentDuration(receiverDurMs = Long.MIN_VALUE + 1, knownDurationMs = 5_400_000))
    }

    @Test
    fun `sin duracion conocida vale la del receptor`() {
        assertEquals(300_000, CastProgress.contentDuration(receiverDurMs = 300_000, knownDurationMs = 0))
    }

    @Test
    fun `una duracion invalida del receptor no se muestra`() {
        // TIME_UNSET sin duración conocida: 0 significa "no sé", y la barra ya sabe manejarlo.
        assertEquals(0, CastProgress.contentDuration(receiverDurMs = Long.MIN_VALUE + 1, knownDurationMs = 0))
    }

    @Test
    fun `una posicion negativa no se guarda`() {
        assertNull(CastProgress.toSave(reportedPosMs = -1, reportedDurMs = 300_000, baseOffsetMs = 0, knownDurationMs = 0))
    }
}
