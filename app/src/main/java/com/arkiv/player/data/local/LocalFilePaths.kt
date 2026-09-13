package com.arkiv.player.data.local

import com.arkiv.player.playback.VideoContainer
import java.io.File

/**
 * Nombres y rutas de los archivos guardados en el dispositivo. Puro a propósito (no toca `Context`)
 * para poder testearlo sin Robolectric: quien conoce el directorio raíz es
 * `LocalDownloadManager`, que lo saca de `getExternalFilesDir(DIRECTORY_MOVIES)`.
 */
object LocalFilePaths {

    private const val DEFAULT_EXT = "mp4"

    fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /**
     * El nombre destino es el `episodeId` sanitizado + la extensión del origen. Se usa el episodeId y
     * no el título del release porque es la clave con la que después se busca el archivo al dar play,
     * y así el mapeo es directo sin depender de la tabla.
     */
    fun fileNameFor(episodeId: String, sourceName: String?): String {
        // La extensión sale de la lista ÚNICA de contenedores y normalizada como URL, no de un
        // corte por el último punto: a la descarga de la NUC le llega una URL de página como
        // nombre de origen, y sobre `https://sitio.com/peli` ese corte devuelve `"com/peli"`.
        // Todo lo demás después de un punto es parte del título.
        val ext = sourceName?.let { VideoContainer.videoExtension(it) } ?: DEFAULT_EXT
        return "${sanitize(episodeId)}.$ext"
    }

    /** Archivo parcial: se escribe acá y se renombra al final, para que nunca exista un destino a medias. */
    fun partOf(file: File): File = File(file.parentFile, file.name + ".part")

    /**
     * Marca de ORIGEN del parcial: guarda de qué URL (o de qué ítem) salieron los bytes que ya están
     * en el `.part`, para no reanudar contra otra fuente.
     *
     * Sin esto, un reintento podría pedir `Range: bytes=<40%>-` contra una URL de origen distinta
     * a la que dejó ese mismo `.part` a medio bajar (por ejemplo si el link firmado venció y se
     * resuelve de nuevo): el server responde 206, se appendea la cola de un archivo al prefijo de
     * otro, y la verificación de tamaño no lo detecta porque las cuentas cierran. El resultado se
     * marcaba "Listo" y era basura. (El caso original que motivó esto era el toggle
     * `downloadQuality` original/derivative de archive.org, borrado en la poda de esta rama; el
     * riesgo de fondo — reanudar un `.part` contra una fuente distinta a la que lo escribió — sigue
     * existiendo con Magis, así que la marca se queda.)
     *
     * Va como archivo hermano y no como columna de la tabla a propósito: el descargador es puro
     * HTTP + disco (no conoce Room), y así el par `.part`/marca viaja junto y lo barre la misma
     * limpieza por prefijo de `LocalDownloadManager.remove`.
     */
    fun originOf(file: File): File = File(file.parentFile, partOf(file).name + ".src")
}
