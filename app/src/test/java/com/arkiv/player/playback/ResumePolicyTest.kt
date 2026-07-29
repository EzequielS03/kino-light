package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Desde dónde reanudar un episodio con lo que quedó guardado.
 *
 * Se saca del ViewModel y se fija acá porque es la regla que decide si al abrir algo aparecés donde
 * lo dejaste o de vuelta en el minuto cero, y hasta ahora vivía enredada con una consulta al motor
 * de torrents que la volvía imposible de probar.
 */
class ResumePolicyTest {

    @Test
    fun `se reanuda donde quedo`() {
        assertEquals(600_000, ResumePolicy.startPosition(savedPositionMs = 600_000, savedDurationMs = 5_400_000))
    }

    @Test
    fun `los primeros diez segundos no cuentan`() {
        // Abrir algo, arrepentirse y salir no debería dejar una marca que después haya que saltear.
        assertEquals(0, ResumePolicy.startPosition(savedPositionMs = 9_000, savedDurationMs = 5_400_000))
    }

    @Test
    fun `exactamente diez segundos tampoco`() {
        assertEquals(0, ResumePolicy.startPosition(savedPositionMs = 10_000, savedDurationMs = 5_400_000))
    }

    @Test
    fun `si ya casi terminaba, se empieza de nuevo`() {
        // Al 90% se considera visto: reanudar ahí te dejaría en los créditos.
        assertEquals(0, ResumePolicy.startPosition(savedPositionMs = 4_900_000, savedDurationMs = 5_400_000))
    }

    @Test
    fun `sin duracion guardada igual se reanuda`() {
        // No saber cuánto dura no es motivo para perder la posición.
        assertEquals(600_000, ResumePolicy.startPosition(savedPositionMs = 600_000, savedDurationMs = 0))
    }

    @Test
    fun `un torrent se reanuda aunque esa zona no este descargada`() {
        // El comportamiento que se cambió: antes, si la zona no estaba bajada se devolvía 0 y el
        // torrent SIEMPRE arrancaba de cero. Ahora se respeta y el motor prioriza esas piezas al
        // pedirse el rango. La regla no depende del estado de la descarga.
        assertEquals(3_000_000, ResumePolicy.startPosition(savedPositionMs = 3_000_000, savedDurationMs = 5_400_000))
    }
}
