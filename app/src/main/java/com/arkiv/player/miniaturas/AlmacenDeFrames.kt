package com.arkiv.player.miniaturas

import java.io.File
import java.io.IOException
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

    /**
     * Sobrescribe: un capítulo tiene UNA imagen viva, nunca dos.
     *
     * Escribe a un temporal y lo renombra encima del destino, que dentro del mismo sistema de
     * archivos es atómico: o queda el frame anterior, o queda el nuevo entero, nunca un JPEG a
     * medias. Escribir directo sobre el destino sí deja truncados si el proceso muere a mitad, y
     * eso es peor que no tener frame: el archivo roto existe, así que [rutaSiExiste] lo devuelve,
     * `EleccionDeMiniatura` lo elige, Coil no lo puede decodificar y la tarjeta queda VACÍA en vez
     * de caer al still de TMDB — hasta que se vuelva a reproducir ese capítulo.
     *
     * El nombre del temporal lleva un sufijo único porque dos capturas del mismo capítulo pueden
     * solaparse (pausar y salir enseguida): con un nombre fijo se pisarían el temporal entre ellas.
     */
    fun guardar(episodeId: String, jpeg: ByteArray): File {
        dir.mkdirs()
        val destino = archivoDe(episodeId)
        // Se barren los temporales viejos de ESTE capítulo antes de escribir: si el proceso murió a
        // mitad de una escritura anterior, ese temporal no lo reclamaría nadie más (el borrado por
        // capítulo solo conoce el `.jpg` del destino) y quedaría en disco para siempre.
        temporalesDe(destino)?.forEach { it.delete() }
        val temporal = File(dir, "${destino.name}.${System.nanoTime()}$SUFIJO_TEMPORAL")
        try {
            temporal.writeBytes(jpeg)
            if (!temporal.renameTo(destino)) throw IOException("no se pudo publicar el frame de $episodeId")
        } catch (e: Throwable) {
            temporal.delete()
            throw e
        }
        return destino
    }

    fun rutaSiExiste(episodeId: String): String? = archivoDe(episodeId).takeIf { it.exists() }?.absolutePath

    fun borrar(episodeId: String) {
        val destino = archivoDe(episodeId)
        destino.delete()
        temporalesDe(destino)?.forEach { it.delete() }
    }

    private fun temporalesDe(destino: File): Array<File>? = dir.listFiles { archivo ->
        archivo.name.startsWith("${destino.name}.") && archivo.name.endsWith(SUFIJO_TEMPORAL)
    }

    /**
     * Vacía el directorio entero. Es el reclamo de logout y no el de un capítulo: la identidad
     * nueva no puede quedarse con los JPEG de escenas de lo que miró la persona anterior.
     *
     * Barre TODO lo que haya, no solo los `.jpg`: así se lleva también cualquier temporal que haya
     * quedado de una escritura interrumpida.
     */
    fun borrarTodo() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        /** Extensión de los temporales de [guardar]: nunca es la del destino, que es `.jpg`. */
        const val SUFIJO_TEMPORAL = ".tmp"
    }
}
