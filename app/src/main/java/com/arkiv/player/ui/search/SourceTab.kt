package com.arkiv.player.ui.search

import com.arkiv.player.ui.catalog.PlaySource

/**
 * Filtro por origen de la lista de resultados. Con varias secciones abiertas a la vez la pantalla se
 * vuelve un muro: esto deja ver un solo origen cuando ya sabes cuál quieres.
 *
 * El orden acá manda: es el de los chips y el de las secciones de "Todo". Magis primero y Caracol
 * después.
 */
enum class SourceTab(val label: String) {
    TODO("Todo"),
    MAGIS("Magis"),
    CARACOL("Caracol"),
}

/** La pestaña a la que pertenece una fuente. */
fun tabOf(source: PlaySource): SourceTab = when (source) {
    is PlaySource.Magis -> SourceTab.MAGIS
    is PlaySource.Ditu -> SourceTab.CARACOL
}

/** Cuántas fuentes hay por pestaña (incluida TODO), para pintarlo en el chip. Siempre devuelve una
 *  clave por cada pestaña, aunque esté en cero, así los chips no bailan mientras van llegando
 *  resultados de cada origen. */
fun countsByTab(sources: List<PlaySource>): Map<SourceTab, Int> {
    val counts = sources.groupingBy { tabOf(it) }.eachCount()
    return SourceTab.entries.associateWith { tab ->
        if (tab == SourceTab.TODO) sources.size else counts[tab] ?: 0
    }
}

fun filterByTab(sources: List<PlaySource>, tab: SourceTab): List<PlaySource> =
    if (tab == SourceTab.TODO) sources else sources.filter { tabOf(it) == tab }

/**
 * Las fuentes a dibujar como filas en los resultados del TV: en el orden del enum, sin las vacías
 * y respetando el filtro elegido.
 *
 * Vive acá y no en la pantalla porque es la única parte de "cómo se ve" que se puede probar sin
 * Compose, y es justo la que decide si una fuente se pierde de vista — que era el problema: con
 * 536 torrents y 20 de magis en una sola lista vertical, magis no existía.
 */
fun filasVisibles(sources: List<PlaySource>, tab: SourceTab): List<Pair<SourceTab, List<PlaySource>>> {
    val porFuente = sources.groupBy { tabOf(it) }
    return SourceTab.entries
        .filter { it != SourceTab.TODO && (tab == SourceTab.TODO || it == tab) }
        .mapNotNull { fuente -> porFuente[fuente]?.takeIf { it.isNotEmpty() }?.let { fuente to it } }
}
