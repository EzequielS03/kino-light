package com.arkiv.player.torrent

import com.arkiv.player.playback.ContenedorDeVideo

/**
 * Empareja los archivos de subtítulos que vienen DENTRO de un torrent con el video servido — robado del
 * patrón de Alfa (servers/torrent.py:444, copiar los .srt que acompañan al video). Muchos releases de
 * escena y packs traen `.srt`/`.ass` sueltos junto al video (o en una carpeta "Subs/"); esto los detecta
 * para priorizar su descarga y ofrecerlos como pista, en vez de depender solo de subtítulos online.
 */
object SubtitleFilePicker {
    val SUB_EXT = setOf("srt", "ass", "ssa", "sub", "vtt")
    private val SUB_DIRS = setOf("subs", "subtitles", "subtitulos", "subtítulos", "sub")

    /**
     * Índices de archivos de subtítulo que acompañan a [videoPath], por prioridad:
     *  1) mismo nombre base que el video (ej. `movie.mkv` ↔ `movie.es.srt`),
     *  2) dentro de una carpeta de subtítulos (`Subs/`, `Subtitles/`…),
     *  3) si el torrent tiene un solo video, cualquier subtítulo.
     * [files] son pares (índice, ruta). Devuelve lista vacía si no hay subtítulos que encajen.
     */
    fun pick(files: List<Pair<Int, String>>, videoPath: String): List<Int> {
        val subs = files.filter { ext(it.second) in SUB_EXT }
        if (subs.isEmpty()) return emptyList()
        val videoBase = baseName(videoPath)

        // 1) mismo nombre base (tolerando sufijos de idioma: "movie.es.srt" → base "movie").
        val sameName = subs.filter {
            val sb = subBase(it.second)
            sb.isNotEmpty() && (sb.startsWith(videoBase) || videoBase.startsWith(sb))
        }
        if (sameName.isNotEmpty()) return sameName.map { it.first }

        // 2) en una carpeta de subtítulos.
        val inSubDir = subs.filter { parentDir(it.second) in SUB_DIRS }
        if (inSubDir.isNotEmpty()) return inSubDir.map { it.first }

        // 3) torrent de un solo video → todos los subs le pertenecen.
        val videoCount = files.count { ContenedorDeVideo.esVideo(it.second) }
        if (videoCount <= 1) return subs.map { it.first }

        return emptyList()
    }

    private fun ext(path: String) = path.substringAfterLast('.', "").lowercase()
    private fun fileName(path: String) = path.replace('\\', '/').substringAfterLast('/')
    private fun parentDir(path: String) =
        path.replace('\\', '/').substringBeforeLast('/', "").substringAfterLast('/').lowercase()

    /** Nombre base del video sin extensión, en minúsculas. */
    private fun baseName(path: String) = fileName(path).substringBeforeLast('.', fileName(path)).lowercase()

    /** Nombre base del subtítulo sin la extensión de sub NI el sufijo de idioma (.es/.spa/.lat/.en…). */
    private fun subBase(path: String): String {
        var b = fileName(path).substringBeforeLast('.', "").lowercase() // quita .srt/.ass…
        val lang = b.substringAfterLast('.', "")
        if (lang.isNotEmpty() && lang.length <= 5 && lang.all { it.isLetter() || it == '-' }) {
            b = b.substringBeforeLast('.', b) // quita el sufijo de idioma
        }
        return b
    }
}
