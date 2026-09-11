package com.arkiv.player.playback

/**
 * Desde dónde reanudar un episodio con lo que quedó guardado.
 *
 * Vive aparte del ViewModel para poder probarse: es la regla que decide si al abrir algo aparecés
 * donde lo dejaste o de vuelta en cero, y eso no debería depender de nada de Android.
 */
object ResumePolicy {

    /** Debajo de esto no se considera que hayas empezado a ver: abrir y arrepentirse no deja marca. */
    private const val MINIMO_MS = 10_000L

    /** A partir de acá se considera visto; reanudar ahí te dejaría en los créditos. */
    private const val CASI_TERMINADO = 0.9

    /**
     * @param savedPositionMs posición guardada.
     * @param savedDurationMs duración guardada, o 0 si no se conoce.
     *
     * A propósito NO consulta el estado de la descarga. Con torrent (fuente borrada en la poda de
     * esta rama) hacía falta: sin esto se descartaba la posición si esa zona no estaba bajada, y
     * como en un magnet recién abierto nunca lo estaba, arrancaba SIEMPRE desde el principio hasta
     * que el streaming server aprendió a anclar la descarga secuencial en el punto pedido. Magis y
     * Ditu no tienen ese problema -son streaming puro, no hay "zona sin bajar" que consultar-, así
     * que hoy directamente no hace falta el parámetro.
     */
    fun startPosition(savedPositionMs: Long, savedDurationMs: Long): Long {
        if (savedPositionMs <= MINIMO_MS) return 0L
        if (savedDurationMs > 0L && savedPositionMs >= savedDurationMs * CASI_TERMINADO) return 0L
        return savedPositionMs
    }
}
