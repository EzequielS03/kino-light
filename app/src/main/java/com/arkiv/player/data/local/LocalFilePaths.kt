package com.arkiv.player.data.local

import java.io.File

/**
 * Nombres y rutas de los archivos guardados en el dispositivo. Puro a propósito (no toca `Context`)
 * para poder testearlo sin Robolectric: quien conoce el directorio raíz es
 * `LocalDownloadManager`, que lo saca de `getExternalFilesDir(DIRECTORY_MOVIES)`.
 */
object LocalFilePaths {

    /** Extensiones de video plausibles. Todo lo demás después de un punto es parte del título. */
    private val VIDEO_EXT = setOf("mkv", "mp4", "avi", "m4v", "mov", "webm", "ts", "mpg", "mpeg", "ogv", "wmv")

    private const val DEFAULT_EXT = "mp4"

    fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /**
     * El nombre destino es el `episodeId` sanitizado + la extensión del origen. Se usa el episodeId y
     * no el título del release porque es la clave con la que después se busca el archivo al dar play,
     * y así el mapeo es directo sin depender de la tabla.
     */
    fun fileNameFor(episodeId: String, sourceName: String?): String {
        val ext = sourceName?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it in VIDEO_EXT } ?: DEFAULT_EXT
        return "${sanitize(episodeId)}.$ext"
    }

    /** Archivo parcial: se escribe acá y se renombra al final, para que nunca exista un destino a medias. */
    fun partOf(file: File): File = File(file.parentFile, file.name + ".part")

    /** Directorio propio de cada descarga de torrent (libtorrent necesita un savePath por torrent). */
    fun torrentDirName(episodeId: String): String = sanitize(episodeId)
}
