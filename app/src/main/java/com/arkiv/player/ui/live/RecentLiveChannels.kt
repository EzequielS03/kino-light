package com.arkiv.player.ui.live

import com.arkiv.player.data.db.LiveChannelCacheEntity
import com.arkiv.player.data.db.LiveRecentEntity
import com.arkiv.player.data.gateway.LiveChannel

/**
 * Arma la fila de "canales en vivo recientes" del home (celular y TV): cruza lo último visto
 * ([recientes], YA ordenado por `vistoAt DESC` -- ver `LiveRecentDao.flowRecent`, esta función NO
 * reordena) con la caché local de canales ([cache], indexada por `code`) para completar logo y
 * número, que `live_recents` no guarda.
 *
 * Why cross-reference the cache instead of storing logo/number in `live_recents` directly: that
 * table used to sync through PocketBase (`cloudsync/CloudSyncManager`, both removed with this
 * branch's pruning) -- back then, adding presentation-only columns meant touching the schema,
 * writing a Room migration, AND deciding whether that field should travel between devices. Cloud
 * sync is gone now, but the schema/migration cost of adding a column is unrelated to that and
 * still applies -- and `live_channels_cache` (which ALREADY stores logo/numero, and never needed
 * to sync since it's a reconstructible cache) solves the same need without touching any of that.
 * The cost of this shortcut is that the cache is keyed by the portal's category and may not have
 * one specific channel (one seen recently whose category was never reloaded): that's why, when
 * `code` doesn't show up in [cache], the result falls back to `numero = 0` and `logo = null` --
 * "unknown" values, not an error. Whoever paints the card treats `logo == null` with the same
 * criterion `ChannelCard` in `LiveScreen.kt` already uses (grayed out + large text instead of a
 * broken logo), except there not even the number is known, so the card falls back further, to
 * the name's initials.
 */
fun canalesRecientesParaHome(
    recientes: List<LiveRecentEntity>,
    cache: Map<String, LiveChannelCacheEntity>,
): List<LiveChannel> = recientes.map { r ->
    val cacheado = cache[r.code]
    LiveChannel(
        code = r.code,
        // El nombre sale de `recientes`, no de la caché: es el que el usuario vio al abrir el
        // canal, y sigue siendo válido aunque la caché de esa categoría esté vieja o ausente.
        nombre = r.nombre,
        numero = cacheado?.numero ?: 0,
        logo = cacheado?.logo,
    )
}
