package com.arkiv.player.data.local

/**
 * Una descarga web tiene DOS fases en serie (la NUC baja del origen, y después el dispositivo baja de
 * la NUC), pero la UI muestra una sola barra. Cada fase ocupa la mitad, así que la barra avanza
 * siempre hacia adelante en vez de volver a cero al cambiar de fase.
 */
object StagingProgress {

    fun fromStaging(jobProgress: Float): Float = (jobProgress.coerceIn(0f, 1f)) * 0.5f

    fun fromTransfer(bytesDone: Long, totalBytes: Long): Float {
        if (totalBytes <= 0) return 0.5f
        val ratio = (bytesDone.toFloat() / totalBytes).coerceIn(0f, 1f)
        return 0.5f + ratio * 0.5f
    }
}
