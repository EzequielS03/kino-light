package com.arkiv.player.data.local

import com.arkiv.player.data.db.DownloadRow

/**
 * Encuentra la descarga que le corresponde a una FUENTE del buscador.
 *
 * En la biblioteca cada fila ya es un capítulo y se busca por `episodeId`. En el buscador no: una
 * fila es una fuente (un release, un resultado web, un ítem de archive.org) y el capítulo recién
 * existe cuando alguien lo baja — el id de torrent, por ejemplo, sale del infohash que solo se
 * conoce después de resolver el magnet contra la red. Así que se busca por lo que la fila SÍ sabe
 * de antemano:
 *
 * - **web**: la URL de su página, que se guarda tal cual en `episodes.torrentData`.
 * - **torrent**: el infohash, que queda al final del `episodeId` (`torrent:anime:<id>::<hash>`).
 * - **archive**: el identifier del ítem, que ES el `itemId` de sus capítulos.
 *
 * Devuelve la FILA, no solo el estado: cancelar o borrar necesitan el `episodeId`, que es
 * justamente lo que la pantalla no podía saber sola.
 *
 * Cuando varias filas matchean (un ítem de archive con varios capítulos encolados) gana la más
 * avanzada: para una fila que resume un ítem entero, "una está bajando" dice más que "una espera".
 */
object DescargasPorFuente {

    fun deWeb(filas: List<DownloadRow>, pageUrl: String?): DownloadRow? =
        resumir(filas.filter { !pageUrl.isNullOrBlank() && it.sourceRef == pageUrl })

    fun deTorrent(filas: List<DownloadRow>, infoHash: String?): DownloadRow? {
        val hash = infoHash?.trim()?.lowercase()?.ifBlank { null } ?: return null
        return resumir(filas.filter { it.episodeId.lowercase().endsWith("::$hash") })
    }

    fun deArchive(filas: List<DownloadRow>, identifier: String?): DownloadRow? =
        resumir(filas.filter { !identifier.isNullOrBlank() && it.itemId == identifier })

    /**
     * Prioridad: lo que está en curso manda sobre lo que espera, y lo que espera manda sobre lo ya
     * terminado — porque lo interesante de una fila con varias descargas es lo que todavía se mueve.
     * Un fallo solo se anuncia si NADA más está pasando: con una bajando y otra fallida, decir
     * "falló" sería mentir sobre el estado del conjunto.
     */
    private fun resumir(filas: List<DownloadRow>): DownloadRow? {
        if (filas.isEmpty()) return null
        fun conEstado(esperado: (EstadoDeDescarga) -> Boolean) =
            filas.firstOrNull { esperado(EstadoDeDescargaDeCapitulo.de(it)) }
        return conEstado { it is EstadoDeDescarga.Bajando }
            ?: conEstado { it == EstadoDeDescarga.EnCola }
            ?: conEstado { it == EstadoDeDescarga.PideConfirmacion }
            ?: conEstado { it == EstadoDeDescarga.Lista }
            ?: conEstado { it is EstadoDeDescarga.Fallida }
    }
}
