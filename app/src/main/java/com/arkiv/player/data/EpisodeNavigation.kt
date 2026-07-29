package com.arkiv.player.data

/** Un episodio reducido a lo que hace falta para navegar entre vecinos. */
data class NavEpisode(val id: String, val section: String)

/**
 * Siguiente/anterior episodio dentro de la MISMA sección (temporada). Pura: testeable sin Room.
 * La lista viene ya ordenada por el DAO; acá solo se busca el vecino que comparta sección.
 */
object EpisodeNavigation {

    fun nextId(all: List<NavEpisode>, currentId: String): String? =
        neighbour(all, currentId) { idx -> all.drop(idx + 1) }

    fun prevId(all: List<NavEpisode>, currentId: String): String? =
        neighbour(all, currentId) { idx -> all.take(idx).asReversed() }

    private fun neighbour(
        all: List<NavEpisode>,
        currentId: String,
        candidates: (Int) -> List<NavEpisode>,
    ): String? {
        val idx = all.indexOfFirst { it.id == currentId }
        if (idx < 0) return null
        val section = all[idx].section
        return candidates(idx).firstOrNull { it.section == section }?.id
    }
}
