package com.arkiv.player.data.local

import com.arkiv.player.data.SeriesItemIds

/**
 * Origen de un episodio de la biblioteca: su id + (solo torrent) el índice del archivo dentro del
 * torrent. Es también la proyección de `DownloadDao.completedOrigins`, así que los nombres de los
 * campos son los alias de esa consulta.
 */
data class EpisodeOrigin(val episodeId: String, val torrentFileIndex: Int?)

/**
 * ¿Este episodio es el MISMO contenido que algo que ya está descargado en el dispositivo?
 *
 * Hace falta porque la misma serie puede estar guardada bajo dos ítems distintos (entró por la
 * pantalla de anime y por la de series; ver [SeriesItemIds.canonicalSeriesId], que cierra el agujero
 * de acá en adelante pero no toca lo ya guardado). Sin esto, tocar "descargar" en el ítem duplicado
 * baja de nuevo los mismos gigabytes que ya están en disco.
 *
 * La identidad NO es el episodeId: es el ORIGEN del capítulo, que ya viaja dentro del propio id.
 * Verificado en [com.arkiv.player.data.ArkivRepository]:
 *
 * - `addWebSeriesEpisode` → `web:series:<seriesId>::<hash de la pageUrl>`. El sufijo depende SOLO
 *   de la pageUrl, así que dos ítems distintos del mismo capítulo comparten sufijo. Este es el caso
 *   real que motivó todo esto.
 * - `addSeriesEpisode` / `addSeriesEpisodeMagnet` → `torrent:series:<seriesId>::<infohash>`, y
 *   `addAnimeEpisode` → `torrent:anime:<anilistId>::<infohash>`. El sufijo es el infohash; el
 *   archivo elegido dentro del torrent vive aparte, en `episodes.torrentFileIndex`, así que la
 *   clave lo incluye ("el infohash + el archivo"). Si los dos caminos eligieran archivos distintos
 *   del mismo pack, no se detecta el duplicado y se baja igual: preferimos bajar de más antes que
 *   bloquear una descarga legítima.
 * - archive.org (`addItem`) → el itemId ES el identifier de archive.org, único; el mismo capítulo
 *   nunca puede estar bajo dos ítems distintos. Idem películas web (`addWebSource`, `web:<hash de
 *   la pageUrl>::0`) y torrent suelto (`addTorrent`/`addTorrentMagnet`, `torrent:<infohash>::…`),
 *   donde el sufijo es un índice de archivo y compararlo entre ítems sería directamente incorrecto.
 *   Para todos esos: sin clave, o sea sin chequeo — no lo necesitan.
 *
 * Puro y sin Room, como [DownloadQueuePolicy]/[FreeSpacePolicy]/[TorrentSizeGate]: la consulta la
 * hace [LocalDownloadManager] y la decisión se toma acá.
 */
object DuplicateDownloadPolicy {

    /**
     * Clave de origen de [origin], o `null` si ese episodio no puede tener un gemelo bajo otro ítem
     * (ver el KDoc del objeto). Dos episodios con la misma clave son literalmente el mismo archivo.
     */
    fun originKeyOf(origin: EpisodeOrigin): String? {
        val id = origin.episodeId
        val suffix = id.substringAfterLast("::", "")
        if (suffix.isBlank() || suffix == id) return null
        return when {
            id.startsWith(SeriesItemIds.WEB_SERIES_PREFIX) -> "web:$suffix"
            id.startsWith(SeriesItemIds.TORRENT_SERIES_PREFIX) ||
                id.startsWith(SeriesItemIds.TORRENT_ANIME_PREFIX) ->
                "torrent:$suffix#${origin.torrentFileIndex ?: -1}"
            else -> null
        }
    }

    /**
     * episodeId de una descarga YA COMPLETADA con el mismo origen que [target], o `null` si no hay.
     * [completed] son las filas de `downloads` en estado `completed` con su `torrentFileIndex`.
     *
     * El propio [target] se excluye: que un episodio ya esté bajado es asunto de la cola
     * ([LocalDownloadManager.enqueue] lo corta antes), no de esta detección entre ítems.
     */
    fun completedDuplicateOf(target: EpisodeOrigin, completed: List<EpisodeOrigin>): String? {
        val key = originKeyOf(target) ?: return null
        return completed
            .firstOrNull { it.episodeId != target.episodeId && originKeyOf(it) == key }
            ?.episodeId
    }

    /**
     * Aviso para el usuario cuando la cola salteó [skipped] descargas por duplicado. `null` = no hay
     * nada que avisar. Un pack manda TODOS sus resultados juntos para que salga un solo aviso y no
     * uno por capítulo.
     */
    fun skippedNotice(skipped: Int): String? = when {
        skipped <= 0 -> null
        skipped == 1 -> "Ya lo tenés descargado en el dispositivo"
        else -> "$skipped capítulos ya estaban descargados en el dispositivo"
    }

    /**
     * ¿Se puede borrar el archivo de [filePath] al quitar [episodeId] de la cola?
     *
     * No, si hay OTRA fila apuntando al mismo archivo. Eso pasa cuando el worker adopta el archivo
     * de un gemelo en vez de re-descargarlo (ver `LocalDownloadWorker`): las dos filas comparten
     * `filePath`, y borrarlo desde una dejaría a la otra diciendo "listo" sobre un archivo que ya no
     * está. [others] son los episodeId de las demás filas que declaran ese mismo `filePath`.
     */
    fun canDeleteFile(others: List<String>): Boolean = others.isEmpty()

    /** Rótulo de la fila que se saltó porque el archivo ya estaba en disco bajo otro ítem. */
    const val ADOPTED_REASON = "Ya estaba descargado"
}
