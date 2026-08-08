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

    /**
     * Marca de ORIGEN del parcial: guarda de qué URL (o de qué ítem) salieron los bytes que ya están
     * en el `.part`, para no reanudar contra otra fuente.
     *
     * Sin esto, cambiar `settings.downloadQuality` a mitad de una descarga de archive.org hacía que
     * el reintento pidiera `Range: bytes=<40% del derivative>-` sobre el `original`: el server
     * responde 206, se appendea la cola de un archivo al prefijo de otro, y la verificación de
     * tamaño no lo detecta porque las cuentas cierran. El resultado se marcaba "Listo" y era basura.
     *
     * Va como archivo hermano y no como columna de la tabla a propósito: el descargador es puro
     * HTTP + disco (no conoce Room), y así el par `.part`/marca viaja junto y lo barre la misma
     * limpieza por prefijo de `LocalDownloadManager.remove`.
     */
    fun originOf(file: File): File = File(file.parentFile, partOf(file).name + ".src")

    /** Directorio propio de cada descarga de torrent (libtorrent necesita un savePath por torrent). */
    fun torrentDirName(episodeId: String): String = sanitize(episodeId)
}
