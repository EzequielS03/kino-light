package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cuánto puede estar quieto el reloj del reproductor antes de que eso sea un estancamiento.
 *
 * MEDIDO EN EL FIRE TV el 2026-08-14, con un canal en vivo andando perfecto durante 4,5 minutos
 * (cero 403, cero 502, cero segmentos cortados, cero pérdidas de imagen):
 *
 * ```
 * 11:24:29.058  PAUSA (buffering) en pos=185008ms  buf=100.0%
 * 11:24:29.570  REANUDO tras 515ms                 (pos=186009ms)
 * 11:24:38.083  PAUSA (buffering) en pos=194016ms  buf=100.0%
 * 11:24:38.584  REANUDO tras 502ms                 (pos=195017ms)
 * 11:25:00.152  PAUSA (buffering) en pos=215987ms  buf=100.0%
 * 11:25:00.652  REANUDO tras 501ms                 (pos=216971ms)
 * ```
 *
 * `buf=100.0%`: el buffer estaba LLENO. Un corte por falta de datos con el buffer al 100% no
 * existe. Y al reanudar la posición había avanzado 1001, 1001 y 984 ms: en vivo el reloj de VLC
 * avanza **de a ~1 segundo**, no de corrido.
 *
 * El umbral de VOD son 900 ms, o sea JUSTO por debajo de ese tick. Cada tanto pasan más de 900 ms
 * sin que el número cambie y marcamos estancamiento sobre un video que se ve bien — con la UI
 * mostrando el spinner medio segundo. Que fueran 4 y no 40 es porque queda al filo.
 */
class DeteccionDeEstancamientoTest {

    @Test fun `el umbral del vivo esta por encima del tick medido de un segundo`() {
        // El tick medido es ~1000 ms. Un umbral que no lo supere con margen genera pausas falsas.
        assert(DeteccionDeEstancamiento.umbralMs(SourceKind.LIVE) > 1_000L) {
            "el umbral del vivo (${DeteccionDeEstancamiento.umbralMs(SourceKind.LIVE)}ms) no supera el tick"
        }
    }

    /** Las fuentes de archivo se quedan como estaban: ahí el reloj avanza de corrido. */
    @Test fun `el resto conserva el umbral de siempre`() {
        listOf(SourceKind.ARCHIVE, SourceKind.MAGIS, SourceKind.LOCAL)
            .forEach { assertEquals("kind=$it", 900L, DeteccionDeEstancamiento.umbralMs(it)) }
        assertEquals(900L, DeteccionDeEstancamiento.umbralMs(null))
    }

    /** El caso exacto que se midió: ~1 s quieto en vivo NO es un estancamiento. */
    @Test fun `un segundo quieto en vivo no es estancamiento`() {
        assert(!DeteccionDeEstancamiento.hayEstancamiento(1_001L, SourceKind.LIVE))
        assert(!DeteccionDeEstancamiento.hayEstancamiento(1_500L, SourceKind.LIVE))
    }

    /** Pero un corte de verdad en vivo se sigue detectando. */
    @Test fun `un corte real en vivo si se detecta`() {
        assert(DeteccionDeEstancamiento.hayEstancamiento(5_000L, SourceKind.LIVE))
    }

    @Test fun `en VOD un segundo quieto sigue siendo estancamiento`() {
        assert(DeteccionDeEstancamiento.hayEstancamiento(1_001L, SourceKind.MAGIS))
        assert(!DeteccionDeEstancamiento.hayEstancamiento(800L, SourceKind.MAGIS))
    }

    /**
     * El umbral tiene que superar al sondeo del watcher, o el detector dispararía por el ritmo con
     * el que mira en vez de por lo que ve.
     */
    @Test fun `todos los umbrales superan el intervalo de sondeo`() {
        (SourceKind.entries + null).forEach {
            assert(DeteccionDeEstancamiento.umbralMs(it) > DeteccionDeEstancamiento.SONDEO_MS) { "kind=$it" }
        }
    }
}
