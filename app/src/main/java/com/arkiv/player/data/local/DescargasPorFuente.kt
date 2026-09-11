package com.arkiv.player.data.local

import com.arkiv.player.data.db.DownloadRow

/**
 * Finds the download that matches a source-search result row.
 *
 * In the library each row is already a chapter and is looked up by `episodeId`. In the search
 * screen it isn't: a row is a source (a release, a web result) and the chapter only exists once
 * someone downloads it. So it's looked up by what the row already knows beforehand:
 *
 * - **web**: its page URL, stored as-is in `episodes.torrentData`.
 *
 * Returns the ROW, not just the state: canceling or deleting needs the `episodeId`, which is
 * exactly what the screen couldn't know on its own.
 */
object DescargasPorFuente {

    fun deWeb(filas: List<DownloadRow>, pageUrl: String?): DownloadRow? =
        resumir(filas.filter { !pageUrl.isNullOrBlank() && it.sourceRef == pageUrl })

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
