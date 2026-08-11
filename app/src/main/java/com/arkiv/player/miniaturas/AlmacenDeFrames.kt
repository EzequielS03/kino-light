package com.arkiv.player.miniaturas

import java.io.File
import java.security.MessageDigest

/**
 * Dónde vive el JPEG de cada capítulo.
 *
 * Un archivo por capítulo, con el nombre derivado del episodeId por hash: los identifier traen `:`
 * y `/` (`web:series:tt01/1x03`), que no sirven como nombre de archivo. Derivarlo en vez de
 * guardarlo en una columna evita que la fila y el archivo se desincronicen.
 */
class AlmacenDeFrames(private val dir: File) {

    fun archivoDe(episodeId: String): File = File(dir, "${hash(episodeId)}.jpg")

    /** Sobrescribe: un capítulo tiene UNA imagen viva, nunca dos. */
    fun guardar(episodeId: String, jpeg: ByteArray): File {
        dir.mkdirs()
        val destino = archivoDe(episodeId)
        destino.writeBytes(jpeg)
        return destino
    }

    fun rutaSiExiste(episodeId: String): String? = archivoDe(episodeId).takeIf { it.exists() }?.absolutePath

    fun borrar(episodeId: String) {
        archivoDe(episodeId).delete()
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
