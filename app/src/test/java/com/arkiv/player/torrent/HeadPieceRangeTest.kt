package com.arkiv.player.torrent

import org.junit.Assert.assertEquals
import org.junit.Test

class HeadPieceRangeTest {
    @Test fun `cabeza arranca en la pieza del offset y cubre headBytes`() {
        // pieceLen=1MB, archivo en offset 100MB, size 50MB, header 16MB → 16 piezas desde la 100.
        val r = TorrentEngine.computeHeadPieceRange(
            pieceLen = 1L * 1024 * 1024, fileOffset = 100L * 1024 * 1024,
            fileSize = 50L * 1024 * 1024, numPieces = 1000, headBytes = 16L * 1024 * 1024,
        )
        assertEquals(100, r.first)
        assertEquals(115, r.last) // 100 + 16 - 1
    }

    @Test fun `no pasa del fin del archivo ni del total de piezas`() {
        // archivo chico (2MB) → la cabeza no excede su última pieza.
        val r = TorrentEngine.computeHeadPieceRange(
            pieceLen = 1L * 1024 * 1024, fileOffset = 0L, fileSize = 2L * 1024 * 1024,
            numPieces = 5, headBytes = 16L * 1024 * 1024,
        )
        assertEquals(0, r.first)
        assertEquals(1, r.last) // fileEnd = 2MB-1 → pieza 1
    }

    @Test fun `pieceLen invalido devuelve rango vacio`() {
        val r = TorrentEngine.computeHeadPieceRange(0L, 0L, 10L, 10, 16L)
        assertEquals(true, r.isEmpty())
    }

    @Test fun `headWin se limita a 16 piezas aunque headBytes pida mas`() {
        // pieceLen=256KB → 16MB de headBytes pedirían 64 piezas, pero se cappea a 16.
        val r = TorrentEngine.computeHeadPieceRange(
            pieceLen = 256L * 1024, fileOffset = 0L,
            fileSize = 100L * 1024 * 1024, numPieces = 1000, headBytes = 16L * 1024 * 1024,
        )
        assertEquals(0, r.first)
        assertEquals(15, r.last) // headWin cappeado a 16 → piezas 0..15
    }

    @Test fun `lastPiece se clampea al total de piezas del torrent`() {
        // pieceLen=1MB, numPieces=4 → sin clamp headEnd sería 15, pero no puede pasar de numPieces-1.
        val r = TorrentEngine.computeHeadPieceRange(
            pieceLen = 1L * 1024 * 1024, fileOffset = 0L,
            fileSize = 100L * 1024 * 1024, numPieces = 4, headBytes = 16L * 1024 * 1024,
        )
        assertEquals(0, r.first)
        assertEquals(3, r.last) // clampeado a numPieces-1 = 3
    }
}
