package com.arkiv.player.data.subtitles

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hash de OpenSubtitles (OSDb): identifica un archivo de vídeo por su TAMAÑO + los primeros y últimos
 * 64 KB, sumados como enteros de 64 bits little-endian (con overflow envolvente). Robado de Torrest
 * (util/hash.go) — es el método nativo de OpenSubtitles para hacer match EXACTO del release, sin depender
 * de imdb/título/temporada. En Arkiv encaja perfecto: la cabeza y la cola ya se descargan en el gate de
 * arranque, así que el hash está disponible apenas empieza la reproducción.
 */
object MovieHash {
    const val CHUNK = 65536

    /** Calcula el hash desde un archivo (los primeros/últimos 64 KB deben estar en disco). */
    fun compute(file: File): String? = runCatching {
        val size = file.length()
        if (size < CHUNK) return null
        RandomAccessFile(file, "r").use { raf ->
            compute(size) { offset, len ->
                val buf = ByteArray(len)
                raf.seek(offset)
                raf.readFully(buf)
                buf
            }
        }
    }.getOrNull()

    /**
     * Núcleo testeable: [fileSize] y un lector `(offset, len) -> ByteArray` para los dos bloques de 64 KB.
     * Devuelve el hash en 16 hex (minúsculas), o null si el archivo es menor que un bloque o falta data.
     */
    fun compute(fileSize: Long, read: (offset: Long, len: Int) -> ByteArray?): String? {
        if (fileSize < CHUNK) return null
        val head = read(0, CHUNK) ?: return null
        val tail = read(fileSize - CHUNK, CHUNK) ?: return null
        if (head.size < CHUNK || tail.size < CHUNK) return null
        val hash = fileSize + sum(head) + sum(tail)
        return String.format("%016x", hash)
    }

    /** Suma los bytes como longs de 64 bits little-endian (overflow envuelve, que es lo que define OSDb). */
    private fun sum(bytes: ByteArray): Long {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var acc = 0L
        repeat(bytes.size / 8) { acc += bb.long }
        return acc
    }
}
