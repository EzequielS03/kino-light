package com.arkiv.player.data.local

import java.util.Locale

/**
 * Aviso antes de bajar un torrent pesado.
 *
 * La comparación es SIEMPRE contra el tamaño del archivo que se va a descargar, nunca contra el del
 * torrent completo: `TorrentResult.sizeBytes` es el peso del pack, y usarlo haría que un pack de
 * temporada de 30 GB con capítulos de 1,2 GB avisara en falso en cada capítulo. Por eso la compuerta
 * corre en el worker, después de resolver la metadata, que es el primer momento en que se conoce el
 * tamaño real del archivo elegido.
 *
 * NO confundir con `SettingsStore.maxTorrentSizeGb` (21 GB por defecto): ese es un filtro que se
 * aplica a los RESULTADOS DE BÚSQUEDA vía `MirrorFilter`, no un aviso de descarga. Los dos valores
 * conviven sin pisarse.
 */
object TorrentSizeGate {

    const val WARN_TORRENT_SIZE_BYTES = 5L * 1024 * 1024 * 1024

    /**
     * [fileSizeBytes] <= 0 significa "no se pudo determinar" y NO dispara: bloquear una descarga por
     * un dato que no tenemos sería peor que dejarla correr.
     */
    fun needsConfirmation(fileSizeBytes: Long, alreadyConfirmed: Boolean): Boolean {
        if (alreadyConfirmed) return false
        if (fileSizeBytes <= 0) return false
        return fileSizeBytes > WARN_TORRENT_SIZE_BYTES
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        else -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
    }
}
