package com.arkiv.player.ui.search

import com.arkiv.player.ui.catalog.PlaySource

/**
 * Filtro por origen de la lista de resultados. Con tres secciones abiertas a la vez la pantalla se
 * vuelve un muro: esto deja ver un solo origen cuando ya sabés cuál querés (p. ej. solo torrent
 * porque vas a descargar, o solo web porque no querés esperar seeds).
 */
enum class SourceTab(val label: String) {
    TODO("Todo"),
    TORRENT("Torrent"),
    WEB("Web"),
    MAGIS("Magis"),
    ARCHIVE("Archive"),
}

/** La pestaña a la que pertenece una fuente. Los packs web cuentan como WEB: para el usuario son
 *  el mismo origen, solo que la serie entera en vez de un capítulo. */
fun tabOf(source: PlaySource): SourceTab = when (source) {
    is PlaySource.Torrent -> SourceTab.TORRENT
    is PlaySource.Web, is PlaySource.WebPack -> SourceTab.WEB
    is PlaySource.Magis -> SourceTab.MAGIS
    is PlaySource.Archive -> SourceTab.ARCHIVE
}

/** Cuántas fuentes hay por pestaña (incluida TODO), para pintarlo en el chip. Siempre devuelve las
 *  cuatro claves, así los chips no bailan mientras van llegando resultados de cada origen. */
fun countsByTab(sources: List<PlaySource>): Map<SourceTab, Int> {
    val counts = sources.groupingBy { tabOf(it) }.eachCount()
    return SourceTab.entries.associateWith { tab ->
        if (tab == SourceTab.TODO) sources.size else counts[tab] ?: 0
    }
}

fun filterByTab(sources: List<PlaySource>, tab: SourceTab): List<PlaySource> =
    if (tab == SourceTab.TODO) sources else sources.filter { tabOf(it) == tab }
