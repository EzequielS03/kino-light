package com.arkiv.player.cast

/** Posición y duración que corresponde persistir para un episodio que se está casteando. */
data class SavedProgress(val positionMs: Long, val durationMs: Long)

/**
 * Clamps what the receiver reports to what makes sense to show or save.
 *
 * Without a transcoder the receiver counts the position and duration of the same file as the
 * phone, so there is no longer an offset to add. The only thing still needed is clamping
 * `C.TIME_UNSET` (a large negative, what a live stream that doesn't know its duration sends) to
 * "unknown" (0), which is what the bar and the progress save already interpret.
 */
object CastProgress {

    /**
     * Posición del CONTENIDO para mostrar en la barra. A diferencia de [toSave], acá siempre hay que
     * devolver algo: la barra se dibuja igual.
     */
    fun contentPosition(receiverPosMs: Long): Long = receiverPosMs.coerceAtLeast(0)

    /**
     * Duración del CONTENIDO para la barra. Devuelve 0 cuando el receptor no la sabe (un directo en
     * vivo manda `TIME_UNSET`), que es lo que la barra ya interpreta como "sin duración".
     */
    fun contentDuration(receiverDurMs: Long): Long = receiverDurMs.coerceAtLeast(0)

    /**
     * @param reportedPosMs posición según el receptor.
     * @param reportedDurMs duración según el receptor (0 o negativa si es un directo en vivo).
     * @return qué guardar, o null si no hay nada confiable que guardar.
     */
    fun toSave(reportedPosMs: Long, reportedDurMs: Long): SavedProgress? {
        if (reportedDurMs <= 0) return null
        if (reportedPosMs < 0) return null
        if (reportedPosMs >= reportedDurMs) return null
        return SavedProgress(reportedPosMs, reportedDurMs)
    }
}
