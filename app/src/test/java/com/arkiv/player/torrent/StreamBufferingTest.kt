package com.arkiv.player.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamBufferingTest {

    // ---- readAheadWindow: ventana proactiva de piezas por delante del cabezal ----

    @Test
    fun `la ventana son las N piezas siguientes con deadlines escalonados`() {
        val w = StreamBuffering.readAheadWindow(currentPiece = 10, lastPiece = 100, size = 4, stepMs = 50)
        assertEquals(listOf(11, 12, 13, 14), w.map { it.piece })
        assertEquals(listOf(50, 100, 150, 200), w.map { it.deadlineMs })
    }

    @Test
    fun `la ventana se clampa en lastPiece y no incluye piezas del siguiente archivo`() {
        val w = StreamBuffering.readAheadWindow(currentPiece = 98, lastPiece = 100, size = 8)
        assertEquals(listOf(99, 100), w.map { it.piece })
    }

    @Test
    fun `en la ultima pieza la ventana es vacia`() {
        assertTrue(StreamBuffering.readAheadWindow(currentPiece = 100, lastPiece = 100, size = 8).isEmpty())
    }

    @Test
    fun `size cero devuelve ventana vacia`() {
        assertTrue(StreamBuffering.readAheadWindow(currentPiece = 10, lastPiece = 100, size = 0).isEmpty())
    }

    @Test
    fun `los deadlines de la ventana crecen de forma monotona`() {
        val ds = StreamBuffering.readAheadWindow(currentPiece = 0, lastPiece = 100, size = 6, stepMs = 40).map { it.deadlineMs }
        assertEquals(ds.sorted(), ds)
        assertTrue(ds.zipWithNext().all { (a, b) -> b > a })
    }

    // ---- headPieces: rango de piezas de la cabeza del archivo para el pre-buffer ----

    @Test
    fun `prebuffer menor a una pieza cubre solo la primera pieza`() {
        val r = StreamBuffering.headPieces(fileOffset = 0, fileSize = 10_000_000, pieceLength = 1_000_000, prebufferBytes = 500_000)
        assertEquals(0..0, r)
    }

    @Test
    fun `prebuffer que cruza varias piezas devuelve el rango completo`() {
        val r = StreamBuffering.headPieces(fileOffset = 0, fileSize = 10_000_000, pieceLength = 1_000_000, prebufferBytes = 4_500_000)
        assertEquals(0..4, r)
    }

    @Test
    fun `respeta el offset del archivo dentro del torrent`() {
        // El archivo empieza en el byte 2_500_000 -> primera pieza 2; 1MB de prebuffer llega a la pieza 3.
        val r = StreamBuffering.headPieces(fileOffset = 2_500_000, fileSize = 10_000_000, pieceLength = 1_000_000, prebufferBytes = 1_000_000)
        assertEquals(2..3, r)
    }

    @Test
    fun `prebuffer mayor al archivo se clampa al tamano del archivo`() {
        val r = StreamBuffering.headPieces(fileOffset = 0, fileSize = 1_500_000, pieceLength = 1_000_000, prebufferBytes = 10_000_000)
        assertEquals(0..1, r)
    }

    @Test
    fun `archivo vacio devuelve rango vacio`() {
        assertTrue(StreamBuffering.headPieces(fileOffset = 0, fileSize = 0, pieceLength = 1_000_000, prebufferBytes = 4_000_000).isEmpty())
    }
}
