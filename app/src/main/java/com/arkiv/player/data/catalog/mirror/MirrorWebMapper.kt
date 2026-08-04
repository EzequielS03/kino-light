package com.arkiv.player.data.catalog.mirror

import com.arkiv.player.data.catalog.web.WebResult

/** Convierte fuentes web del mirror (nuestro backend/DB) al modelo de la app. */
object MirrorWebMapper {
    fun toWebResult(w: MirrorWebSource): WebResult = WebResult(
        siteId = w.siteId,
        siteName = w.siteId,
        title = w.name.ifBlank { "T${w.season}E${w.episode}" },
        year = "",
        pageUrl = w.pageUrl,
        posterUrl = "",
        language = w.langNorm,
        quality = w.quality,
        kind = "tv",
        // La temporada real del mirror viaja con el resultado: es la MISMA fila local (clave = hash de
        // pageUrl) que escribe el camino de packs con `MirrorWebSource.season`, así que descartarla acá
        // obligaba a los caminos de "un episodio suelto" a inventar 1 (ver WebSourceSeason/WebResult).
        season = w.season,
    )

    /** Prefiere las fuentes del mirror; si están vacías, cae a las fuentes en vivo. */
    fun <T> chooseWebSources(mirror: List<T>, live: List<T>): List<T> =
        if (mirror.isNotEmpty()) mirror else live
}
