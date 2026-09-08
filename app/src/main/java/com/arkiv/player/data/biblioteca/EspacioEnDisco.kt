package com.arkiv.player.data.biblioteca

import com.arkiv.player.data.local.DownloadGroup
import com.arkiv.player.data.local.EpisodeDownloadStatus
import com.arkiv.player.data.local.FileSizeFormat

/**
 * Lo que la sección "Descargas" del TV muestra arriba de todo.
 *
 * Existe porque en un Fire TV Stick el disco es chico y se llena sin avisar: guardar una temporada
 * entera encola N descargas y hasta ahora no había ninguna pantalla en el TV donde verlo.
 */
object EspacioEnDisco {

    /**
     * Bytes ya escritos por las descargas, incluidos los parciales (`bytesDone`, no `bytes`): lo que
     * interesa es lo que ocupa AHORA en el disco, no lo que va a ocupar al terminar.
     *
     * Puede sobrecontar en un caso conocido: cuando dos filas comparten el mismo archivo porque una
     * adoptó el de su gemela (ver `LocalDownloadWorker.adoptTwinIfAlreadyDownloaded`), los bytes se
     * cuentan dos veces. Se acepta: es un número informativo, no una decisión, y quien decide si una
     * descarga entra sigue siendo `FreeSpacePolicy` midiendo el disco de verdad.
     */
    fun ocupadoPorDescargas(grupos: List<DownloadGroup>): Long =
        grupos.sumOf { grupo ->
            grupo.episodes.sumOf { ep ->
                (ep.status as? EpisodeDownloadStatus.Tracked)?.row?.bytesDone ?: 0L
            }
        }

    /**
     * "12.0 GB libres  ·  3.0 GB en descargas". Sin nada bajado, omite la segunda cláusula en vez de
     * mostrar un "0 MB" que no le dice nada a nadie.
     *
     * Usa el formateador que ya existe (`FileSizeFormat.formatSize`) en vez de uno propio: dos
     * formatos de tamaño distintos en la misma app se notan.
     */
    fun resumen(libresBytes: Long, ocupadoBytes: Long): String {
        val libres = "${FileSizeFormat.formatSize(libresBytes)} libres"
        return if (ocupadoBytes <= 0) libres
        else "$libres  ·  ${FileSizeFormat.formatSize(ocupadoBytes)} en descargas"
    }
}
