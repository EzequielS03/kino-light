package com.arkiv.player.torrent

/** Una pieza a la que ponerle un deadline, para prefetch proactivo. */
data class PieceDeadline(val piece: Int, val deadlineMs: Int)

/**
 * Lógica pura de prefetch/buffer para el streaming de torrent (testeable sin red/libtorrent).
 * La usan [TorrentStreamServer] (read-ahead) y [TorrentEngine] (pre-buffer de arranque).
 */
object StreamBuffering {

    /**
     * Ventana de read-ahead: las [size] piezas siguientes a [currentPiece], sin pasar de [lastPiece]
     * (última pieza del archivo servido), con deadlines escalonados crecientes ([stepMs], 2·[stepMs], …)
     * para que libtorrent las baje ANTES de que el cabezal de lectura llegue. Vacía si no hay nada por
     * delante o si [size] ≤ 0. El caller filtra las que ya tiene (`havePiece`) y aplica setPieceDeadline.
     */
    fun readAheadWindow(currentPiece: Int, lastPiece: Int, size: Int, stepMs: Int = 50): List<PieceDeadline> {
        if (size <= 0) return emptyList()
        val start = currentPiece + 1
        val end = minOf(currentPiece + size, lastPiece)
        if (start > end) return emptyList()
        return (start..end).mapIndexed { i, p -> PieceDeadline(p, (i + 1) * stepMs) }
    }

    /**
     * Rango de piezas de la CABEZA del archivo que cubren los primeros [prebufferBytes] (o el archivo
     * entero si es menor), para esperar un colchón antes de arrancar la reproducción. [fileOffset] es el
     * offset del archivo dentro del torrent (multi-archivo). Rango vacío si el archivo o la pieza no son
     * válidos. El caller comprueba `havePiece` sobre el rango para saber si ya hay colchón.
     */
    fun headPieces(fileOffset: Long, fileSize: Long, pieceLength: Long, prebufferBytes: Long): IntRange {
        if (fileSize <= 0 || pieceLength <= 0) return IntRange.EMPTY
        val bytes = minOf(prebufferBytes, fileSize).coerceAtLeast(1)
        val firstPiece = (fileOffset / pieceLength).toInt()
        val lastPiece = ((fileOffset + bytes - 1) / pieceLength).toInt()
        return firstPiece..lastPiece
    }
}
