package com.arkiv.player.data.local

import java.util.Locale

/**
 * Formato legible de un tamaño en bytes ("4.2 GB" / "480 MB"). Antes vivía en `TorrentSizeGate`
 * (torrent se borró en la poda de esta rama); lo usan por igual las descargas de archive, magis y
 * el resumen de espacio libre en disco, así que se porta como utilidad genérica.
 */
object FileSizeFormat {
    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        else -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
    }
}
