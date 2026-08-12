package com.arkiv.player.ui.live

import com.arkiv.player.data.db.LiveChannelCacheEntity
import com.arkiv.player.data.db.LiveRecentEntity
import com.arkiv.player.data.gateway.LiveChannel

/**
 * Arma la fila de "canales en vivo recientes" del home (celular y TV): cruza lo último visto
 * ([recientes], YA ordenado por `vistoAt DESC` -- ver `LiveRecentDao.flowUltimos`, esta función NO
 * reordena) con la caché local de canales ([cache], indexada por `code`) para completar logo y
 * número, que `live_recents` no guarda.
 *
 * Por qué cruzar con la caché y no guardar logo/número en `live_recents` directamente: esa tabla
 * sincroniza por PocketBase (ver `cloudsync/CloudSyncManager`), así que sumarle columnas de puro
 * presentación habría significado tocar el esquema, escribir una migración de Room Y decidir si
 * ese campo debe viajar entre dispositivos -- todo por un dato que `live_channels_cache` (que YA
 * guarda logo/numero, y que NO sincroniza por ser caché reconstruible) resuelve sin tocar nada de
 * eso. El costo de este atajo es que esa caché es por categoría del portal y puede no tener un
 * canal puntual (uno visto hace poco cuya categoría nunca se volvió a cargar): por eso, cuando
 * `code` no aparece en [cache], el resultado cae a `numero = 0` y `logo = null` -- valores
 * "desconocido", no un error. Quien pinta la tarjeta trata `logo == null` con el mismo criterio
 * que ya usa `ChannelCard` en `LiveScreen.kt` (degradado + un texto grande en vez de un logo roto),
 * salvo que ahí ni siquiera el número se conoce, así que la tarjeta cae más abajo, a las iniciales
 * del nombre.
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
