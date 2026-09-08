package com.arkiv.player.data.gateway

import com.arkiv.player.data.ArchiveSearchResult
import com.arkiv.player.ui.catalog.PlaySource

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
    "archive" -> PlaySource.Archive(
        ArchiveSearchResult(
            identifier = extra["identifier"].orEmpty(),
            title = title,
            year = year,
            gatewayRef = ref,
        ),
    )

    // Magis se lleva el resultado entero: su `ref` es todo lo que hace falta para resolver, y no
    // hay un tipo previo de la app al que mapearlo.
    "magis" -> PlaySource.Magis(this)

    "ditu" -> PlaySource.Ditu(this)

    // "torrent"/"web": el gateway todavía puede mandarlos (server viejo), pero esta rama del APK
    // ya no sabe qué hacer con ellos (torrent/web se borraron, TODO(task 6)). Se ignoran, igual
    // que cualquier fuente futura desconocida.
    else -> null
}
