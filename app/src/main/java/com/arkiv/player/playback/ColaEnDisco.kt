package com.arkiv.player.playback

import java.io.File
import java.nio.ByteBuffer

/**
 * The last KB of a file, saved to disk so they survive the app's restart.
 *
 * Exists because of a Fire TV measurement from 2026-08-14, opening an MPEG-TS movie for the first
 * time:
 *
 * ```
 * 09:35:12.667  ← pide rango=bytes=660308868-           ← libVLC wanted the END of the file
 * 09:35:13.921  origen rechazó ... con -1 (intento 1/3)
 * 09:35:15.121  origen rechazó ... con -1 (intento 1/3)
 * 09:35:16.320  precalentada la cola: 256KB en 6205ms
 * 09:35:16.386  ⏱ abrió en 5376ms
 * ```
 *
 * With a TS, libVLC used to probe the end of the file to deduce the duration, and until that
 * range arrived there was NO picture (see [ColaCaliente], which already serves it from memory).
 * The comparison from the same day leaves no doubt: the new mp4 asked ONLY for `bytes=0-` and
 * opened in 1525ms; the new mpegts also asked for the end and took 5376ms. And the mpegts files
 * that opened in 393 and 421ms were the same title already seen, with the tail still in memory.
 *
 * That's the waste this fixes: [ColaCaliente] and `abrirConDuplicado` already tackle the probing
 * and the CDN's rejections, but those 256 KB lived in an in-memory map, so EVERY app startup paid
 * the first-time cost again. On a Fire TV, which kills the app as soon as it goes to the
 * background, that's almost always.
 *
 * The key is stable across sessions because it's the SHA-1 of the ORIGIN URL, and magis's doesn't
 * carry the token inside it (it travels in the headers): `…/vod/<contentId>_media.mp4`.
 */
class ColaEnDisco(private val dir: File, private val maxColas: Int = MAX_COLAS) {

    /** Los bytes finales de un archivo y dónde empiezan dentro de él. */
    data class Cola(val inicio: Long, val total: Long, val bytes: ByteArray) {
        // `equals`/`hashCode` a mano: un data class con ByteArray los genera por identidad y eso
        // sorprende. Acá lo que importa es el contenido.
        override fun equals(other: Any?): Boolean = other is Cola &&
            inicio == other.inicio && total == other.total && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int =
            (inicio.hashCode() * 31 + total.hashCode()) * 31 + bytes.contentHashCode()
    }

    private fun archivo(clave: String) = File(dir, "$clave$SUFIJO")

    /**
     * Guarda [bytes] como la cola de [clave]. Una cola vacía no se guarda: serviría para nada y
     * ocuparía una ranura del tope.
     */
    fun guardar(clave: String, inicio: Long, total: Long, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        runCatching {
            dir.mkdirs()
            val cabecera = ByteBuffer.allocate(BYTES_DE_CABECERA).putLong(inicio).putLong(total)
            // Se escribe a un temporal y se renombra: si la app muere a mitad, lo que queda es el
            // temporal y no una cola a medias que se leería como buena.
            val tmp = File(dir, "$clave$SUFIJO.tmp")
            tmp.outputStream().use { it.write(cabecera.array()); it.write(bytes) }
            tmp.renameTo(archivo(clave))
            descartarViejas()
        }
    }

    /**
     * La cola guardada de [clave], o null si no hay o no se puede leer entera.
     *
     * Cualquier problema devuelve null en silencio: una cola ilegible solo significa ir al origen
     * como siempre, que es exactamente lo que se hacía antes de que esto existiera.
     */
    fun leer(clave: String): Cola? = runCatching {
        val f = archivo(clave)
        if (!f.isFile) return null
        val crudo = f.readBytes()
        if (crudo.size <= BYTES_DE_CABECERA) return null
        val buf = ByteBuffer.wrap(crudo)
        val inicio = buf.long
        val total = buf.long
        val bytes = crudo.copyOfRange(BYTES_DE_CABECERA, crudo.size)
        f.setLastModified(System.currentTimeMillis())
        Cola(inicio, total, bytes)
    }.getOrNull()

    /** Deja como mucho [maxColas], tirando las de fecha de modificación más vieja. */
    private fun descartarViejas() {
        val archivos = dir.listFiles { f -> f.name.endsWith(SUFIJO) } ?: return
        if (archivos.size <= maxColas) return
        archivos.sortedBy { it.lastModified() }
            .take(archivos.size - maxColas)
            .forEach { runCatching { it.delete() } }
    }

    companion object {
        /** Dos `Long`: dónde empieza la cola dentro del archivo, y cuánto mide el archivo entero. */
        const val BYTES_DE_CABECERA = 16

        /** ~256 KB cada una. 64 títulos ≈ 16 MB, despreciable al lado de la caché de video. */
        const val MAX_COLAS = 64

        private const val SUFIJO = ".cola"
    }
}
