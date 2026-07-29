package com.arkiv.player.cast

/** Posición y duración que corresponde persistir para un episodio que se está casteando. */
data class SavedProgress(val positionMs: Long, val durationMs: Long)

/**
 * Traduce lo que reporta el receptor a lo que hay que guardar.
 *
 * Hace falta porque con el audio transcodificado el receptor miente por omisión: el stream ya
 * arranca en el punto pedido, así que él cuenta desde cero, y al ser "en vivo" tampoco sabe la
 * duración. Guardar eso crudo pisaría el minuto bueno con un cero.
 */
object CastProgress {

    /**
     * Posición del CONTENIDO para mostrar en la barra. A diferencia de [toSave], acá siempre hay que
     * devolver algo: la barra se dibuja igual.
     */
    fun contentPosition(receiverPosMs: Long, baseOffsetMs: Long): Long =
        (baseOffsetMs + receiverPosMs).coerceAtLeast(0)

    /**
     * Duración del CONTENIDO para la barra. El receptor manda `TIME_UNSET` (negativo) con el stream
     * transcodificado, que sale en vivo; la duración real la conoce el celu porque la leyó del
     * archivo. Devuelve 0 cuando no la sabe nadie, que es lo que la barra ya interpreta como
     * "sin duración".
     */
    fun contentDuration(receiverDurMs: Long, knownDurationMs: Long): Long = when {
        knownDurationMs > 0 -> knownDurationMs
        receiverDurMs > 0 -> receiverDurMs
        else -> 0
    }

    /**
     * @param reportedPosMs posición según el receptor.
     * @param reportedDurMs duración según el receptor (0 si es un stream en vivo).
     * @param baseOffsetMs punto del video donde arrancó el stream transcodificado (0 si va directo).
     * @param knownDurationMs duración real, que el celu sí conoce (0 si no se sabe).
     * @return qué guardar, o null si no hay nada confiable que guardar.
     */
    fun toSave(
        reportedPosMs: Long,
        reportedDurMs: Long,
        baseOffsetMs: Long,
        knownDurationMs: Long,
    ): SavedProgress? {
        val duration = if (knownDurationMs > 0) knownDurationMs else reportedDurMs
        if (duration <= 0) return null
        if (reportedPosMs < 0) return null
        val position = baseOffsetMs + reportedPosMs
        if (position >= duration) return null
        return SavedProgress(position, duration)
    }
}
