package com.arkiv.player.data.gateway

import com.arkiv.player.ui.catalog.PlaySource

/**
 * Traduce un resultado de Magis al modelo que ya usa la pantalla.
 *
 * Devuelve `null` si la fuente no la conoce este APK: `source` es un campo heredado del gateway
 * (Task 9, sub-proyecto 2B, se llevó el servidor entero) que hoy solo puebla [MagisFuente]
 * (com.arkiv.player.data.magis) con `"magis"`, pero se sigue chequeando por si algún día vuelve a
 * traer otro valor.
 *
 * El magnet y la URL de la página NO se rellenan: todo se resuelve al reproducir, mandando el
 * [GatewayResult.ref] directo a `MagisResolve.resolveVod`.
 */
fun GatewayResult.toPlaySource(): PlaySource? = when (source) {
    // Magis se lleva el resultado entero: su `ref` es todo lo que hace falta para resolver, y no
    // hay un tipo previo de la app al que mapearlo.
    "magis" -> PlaySource.Magis(this)

    // "archive"/"torrent"/"web"/"ditu": esta rama del APK ya no sabe qué hacer con ellos (todas se
    // borraron; archive.org y ditu en la poda de esta rama, torrent/web en la Tarea 2 — ditu vuelve
    // en el sub-proyecto 3 con un cliente directo). Se ignoran, igual que cualquier fuente futura
    // desconocida.
    else -> null
}
