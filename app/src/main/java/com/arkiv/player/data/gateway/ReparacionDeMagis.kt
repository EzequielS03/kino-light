package com.arkiv.player.data.gateway

import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.SeasonChapter

/**
 * Repara, una sola vez y en silencio, un ítem de Magis que se guardó sin identidad.
 *
 * Por qué hace falta: el `tmdbId` de la serie —del que cuelgan el nombre real del capítulo, su
 * miniatura y su sinopsis— se escribe al GUARDAR la temporada, no al abrirla. Los ítems que
 * entraron cuando el gateway todavía no sabía identificar la serie se quedaron sin nada, y volver a
 * entrar a la pantalla nunca los arreglaba. Sin esto habría que borrarlos y agregarlos de nuevo a
 * mano, uno por uno.
 *
 * Es best-effort de punta a punta: si el gateway no contesta, si el ref caducó o si la serie sigue
 * sin poder identificarse, la pantalla se dibuja exactamente igual que antes. Nunca lanza.
 *
 * Se salta sola cuando no hay nada que hacer (el ítem no es de Magis, ya tiene su `tmdbId`, o no
 * guardó ref), así que llamarla en cada apertura del detalle no cuesta ni una consulta de red.
 */
suspend fun repararIdentidadDeMagis(
    repository: ArkivRepository,
    api: ContentSource,
    itemId: String,
): Boolean {
    val ref = repository.magisRefToRepair(itemId) ?: return false
    val (capitulos, serie) = runCatching { api.episodesWithSeries(ref) }.getOrElse { return false }

    val tmdbId = serie?.tmdbId?.takeIf { it > 0 }
    val enriquecidos = capitulos.filter {
        it.still != null || it.tmdbTitle != null || it.overview != null
    }
    // Nada que aplicar: el gateway sigue sin poder identificar esta serie (o el portal la cambió).
    // Se sale sin escribir para no marcar como reparado algo que no lo está.
    if (tmdbId == null && enriquecidos.isEmpty()) return false

    repository.applyMagisIdentity(
        itemId,
        tmdbId,
        // El nombre con el que TMDB conoce la serie: es la mitad que faltaba. Reparar solo el
        // `tmdbId` arreglaba las miniaturas y los nombres de capítulo, pero la tarjeta seguía
        // diciendo "Shin seiki evangerion Temp.1" para siempre.
        tituloCanonico = serie?.titulo,
        chapters = capitulos.map {
            SeasonChapter(
                number = it.number, title = it.title, ref = it.ref,
                still = it.still, tmdbTitle = it.tmdbTitle, overview = it.overview,
            )
        },
    )
    android.util.Log.w(
        "ArkivGw",
        "repaired the identity of $itemId: tmdb=$tmdbId · ${enriquecidos.size}/${capitulos.size} chapters with metadata",
    )
    return true
}
