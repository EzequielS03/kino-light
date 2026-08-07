package com.arkiv.player.data.local

/**
 * Decide si una descarga entra en el disco. Puro (no toca `StatFs`) para poder testearlo sin
 * Robolectric: quien mide el espacio real es `LocalDownloadManager`.
 */
object FreeSpacePolicy {

    /** Margen para no dejar el dispositivo al borde: otras apps se rompen antes que Arkiv. */
    const val MARGIN_BYTES = 500L * 1024 * 1024

    /** [neededBytes] <= 0 es "todavía no se sabe": no tiene sentido bloquear por un dato que no hay. */
    fun fits(availableBytes: Long, neededBytes: Long): Boolean {
        if (neededBytes <= 0) return true
        return availableBytes >= neededBytes + MARGIN_BYTES
    }
}
