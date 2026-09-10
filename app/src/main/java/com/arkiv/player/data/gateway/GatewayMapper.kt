package com.arkiv.player.data.gateway

import com.arkiv.player.ui.catalog.PlaySource

/**
 * Traduce un resultado de búsqueda al modelo que ya usa la pantalla.
 *
 * `source` lo ponen las dos fuentes de la búsqueda: `MagisFuente` (com.arkiv.player.data.magis)
 * con `"magis"` y `DituFuente` (com.arkiv.player.data.ditu) con `"ditu"`. Cualquier otro valor
 * devuelve `null`: este APK no sabría qué hacer con él.
 *
 * El magnet y la URL de la página NO se rellenan: todo se resuelve al reproducir, a partir del
 * [GatewayResult.ref].
 */
fun GatewayResult.toPlaySource(): PlaySource? = when (source) {
    // Magis se lleva el resultado entero: su `ref` es todo lo que hace falta para resolver, y no
    // hay un tipo previo de la app al que mapearlo.
    "magis" -> PlaySource.Magis(this)

    // Caracol, igual: su `ref` (`ditu1:<contentType>:<contentId>`, ver `DituRef`) alcanza para
    // resolver y para listar capítulos.
    "ditu" -> PlaySource.Ditu(this)

    // "archive"/"torrent"/"web": se borraron de esta rama (archive.org en la poda de light-magis,
    // torrent/web en la Tarea 2). Se ignoran, igual que cualquier fuente futura desconocida.
    else -> null
}
