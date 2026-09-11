package com.arkiv.player.cast

/** Posición y duración que corresponde persistir para un episodio que se está casteando. */
data class SavedProgress(val positionMs: Long, val durationMs: Long)

/**
 * Acota lo que reporta el receptor a lo que tiene sentido mostrar o guardar.
 *
 * Sin transcodificador el receptor cuenta la posición y la duración del mismo archivo que el celu,
 * así que ya no hay un desfase que sumar. Lo único que sigue haciendo falta es acotar
 * `C.TIME_UNSET` (un negativo grande, lo que manda un directo en vivo que no sabe su duración) a
 * "no sé" (0), que es lo que la barra y el guardado de progreso ya interpretan.
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
