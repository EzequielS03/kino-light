package com.arkiv.player.remote

import kotlin.math.abs

/**
 * Sostiene el efecto de un comando recién enviado hasta que el TV lo confirme.
 *
 * Sin esto, un poll en vuelo con la foto anterior revertiría el botón a la vista medio segundo
 * después de tocarlo. Lo pendiente se suelta cuando el TV confirma (la foto ya refleja lo pedido)
 * o cuando vence [holdMs] — para no quedar pegado si el comando se perdió.
 */
class OptimisticOverlay(private val holdMs: Long = 5_000) {

    private var pendingState: TvPlaybackState? = null
    private var pendingStateAtMs = 0L
    private var pendingPositionMs: Long? = null
    private var pendingPositionAtMs = 0L

    fun expectState(state: TvPlaybackState, nowMs: Long) {
        pendingState = state
        pendingStateAtMs = nowMs
    }

    fun expectPosition(positionMs: Long, nowMs: Long) {
        pendingPositionMs = positionMs
        pendingPositionAtMs = nowMs
    }

    fun clear() {
        pendingState = null
        pendingPositionMs = null
    }

    /**
     * Instante REAL en que se pidió el pin todavía activo tras el último [apply] (el más viejo si
     * hay dos pendientes a la vez), o null si no queda nada pendiente.
     *
     * La UI lo usa para dos cosas: decidir si hay un pin vivo sin comparar valores —necesario
     * porque un pin puede coincidir por pura casualidad con la foto cruda (ej. reanudar cuando la
     * foto vieja, sin poll fresco todavía, ya decía PLAYING: la foto "ajustada" queda idéntica a la
     * cruda aunque el pin siga activo)— y anclar la extrapolación al momento REAL del comando en
     * vez del tick (hasta 250ms más tarde) que lo nota, para no perder ese cuarto de segundo.
     */
    fun pinnedAtMs(): Long? = listOfNotNull(
        pendingState?.let { pendingStateAtMs },
        pendingPositionMs?.let { pendingPositionAtMs },
    ).minOrNull()

    fun apply(snapshot: TvNowPlaying, receivedAtMs: Long, nowMs: Long): TvNowPlaying {
        var out = snapshot

        pendingState?.let { want ->
            // "Confirmado" solo si la foto LLEGÓ DESPUÉS de mandar el comando. Sin esto, reanudar
            // antes de que un poll confirmara la pausa se da por confirmado al instante —la foto
            // vieja sigue diciendo PLAYING— y la barra salta hacia adelante toda la pausa.
            val confirmed = receivedAtMs > pendingStateAtMs && snapshot.state == want
            val expired = nowMs - pendingStateAtMs >= holdMs
            if (confirmed || expired) pendingState = null else out = out.copy(state = want)
        }

        pendingPositionMs?.let { want ->
            val confirmed = receivedAtMs > pendingPositionAtMs &&
                abs(snapshot.positionMs - want) <= SEEK_TOLERANCE_MS
            val expired = nowMs - pendingPositionAtMs >= holdMs
            if (confirmed || expired) pendingPositionMs = null else out = out.copy(positionMs = want)
        }

        return out
    }

    private companion object {
        /** El TV nunca cae exactamente en el ms pedido: se da por confirmado si quedó cerca. */
        const val SEEK_TOLERANCE_MS = 2_000L
    }
}
