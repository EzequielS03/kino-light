package com.arkiv.player.playback

/**
 * Cuánto puede estar quieto el reloj del reproductor antes de que eso sea un estancamiento.
 *
 * El umbral NO puede ser más chico que el TICK de la fuente, o el detector dispara por el ritmo con
 * el que el reloj se actualiza en vez de por que la reproducción se haya frenado.
 *
 * Eso es exactamente lo que pasaba en vivo. Medido en el Fire TV el 2026-08-14, con un canal
 * andando perfecto durante 4,5 minutos —cero 403, cero 502, cero segmentos cortados, cero pérdidas
 * de imagen—, aparecieron cuatro pausas así:
 *
 * ```
 * 11:24:29.058  PAUSA (buffering) en pos=185008ms  buf=100.0%
 * 11:24:29.570  REANUDO tras 515ms                 (pos=186009ms)
 * ```
 *
 * `buf=100.0%`: el buffer estaba LLENO, y un corte por falta de datos con el buffer al 100% no
 * existe. Al reanudar, la posición había avanzado 1001, 1001 y 984 ms: **en vivo el reloj de VLC
 * avanza de a ~1 segundo**, no de corrido — se mueve por segmento, no por frame.
 *
 * Con el umbral de VOD (900 ms) eso queda JUSTO por debajo del tick, así que cada tanto pasan más
 * de 900 ms sin que el número cambie y marcamos estancamiento sobre un video que se ve bien, con la
 * UI mostrando el spinner medio segundo. Que fueran cuatro y no cuarenta es porque queda al filo.
 */
object DeteccionDeEstancamiento {

    /**
     * Fuentes de archivo: el reloj avanza de corrido, así que 900 ms sin moverse ya es raro y
     * conviene avisar rápido. Es el valor que venía de antes y que en VOD nunca dio falsos.
     */
    const val ARCHIVO_MS = 900L

    /**
     * Vivo: 2,5× el tick medido de ~1 s. Con menos margen el detector vuelve a quedar al filo del
     * ritmo de actualización; con esto un corte real se sigue viendo enseguida —2,5 s de imagen
     * quieta ya es evidente— pero el ritmo normal deja de disparar.
     */
    const val VIVO_MS = 2_500L

    /**
     * Cada cuánto mira el watcher. Vive acá y no suelto en el reproductor porque es la otra mitad
     * de la misma decisión: un umbral por debajo del sondeo haría que el detector dispare por el
     * ritmo con el que mira, no por lo que ve.
     */
    const val SONDEO_MS = 500L

    fun umbralMs(kind: SourceKind?): Long = if (kind == SourceKind.LIVE) VIVO_MS else ARCHIVO_MS

    /** Si [msSinAvanzar] de reloj quieto, para esta fuente, ya es un estancamiento de verdad. */
    fun hayEstancamiento(msSinAvanzar: Long, kind: SourceKind?): Boolean =
        msSinAvanzar > umbralMs(kind)
}
