package com.arkiv.player.data.gateway

import com.arkiv.player.data.ArchiveSearchResult
import com.arkiv.player.data.catalog.TorrentLang
import com.arkiv.player.data.catalog.TorrentResult
import com.arkiv.player.data.catalog.web.WebResult
import com.arkiv.player.ui.catalog.PlaySource

/** Idioma del gateway → el enum de la app. Uno desconocido no rompe el mapeo. */
private fun langDe(texto: String): TorrentLang =
    runCatching { TorrentLang.valueOf(texto.uppercase()) }.getOrDefault(TorrentLang.ENGLISH)

/**
 * Traduce un resultado del gateway al modelo que ya usa la pantalla.
 *
 * Devuelve `null` si la fuente no la conoce este APK: el servidor puede sumar fuentes nuevas y
 * un APK viejo simplemente las ignora en vez de romperse.
 *
 * El magnet y la URL de la página NO se rellenan: con el gateway todo se resuelve al reproducir,
 * mandando el [GatewayResult.ref] a `/v1/resolve`.
 */
fun GatewayResult.toPlaySource(): PlaySource? = when (source) {
    "torrent" -> PlaySource.Torrent(
        TorrentResult(
            name = title,
            seeders = seeders,
            sizeBytes = sizeBytes,
            lang = langDe(lang),
            infoHash = extra["infohash"]?.takeIf { it.isNotBlank() },
            gatewayRef = ref,
        ),
    )

    "archive" -> PlaySource.Archive(
        ArchiveSearchResult(
            identifier = extra["identifier"].orEmpty(),
            title = title,
            year = year,
            gatewayRef = ref,
        ),
    )

    "web" -> PlaySource.Web(
        WebResult(
            siteId = extra["site_id"].orEmpty(),
            siteName = extra["site_id"].orEmpty(),
            title = title,
            year = year,
            pageUrl = "",
            posterUrl = "",
            language = lang,
            quality = quality,
            kind = kind,
            season = season.takeIf { it > 0 },
            episode = episode.takeIf { it > 0 },
            gatewayRef = ref,
        ),
    )

    // Magis se lleva el resultado entero: su `ref` es todo lo que hace falta para resolver, y no
    // hay un tipo previo de la app al que mapearlo.
    "magis" -> PlaySource.Magis(this)

    else -> null
}
