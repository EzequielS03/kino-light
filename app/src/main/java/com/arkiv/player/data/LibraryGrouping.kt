package com.arkiv.player.data

import com.arkiv.player.data.db.ArtworkEntity
import com.arkiv.player.data.db.LibraryRow

/**
 * Una serie de la biblioteca vista como UNA sola cosa, con todas las adquisiciones que la
 * componen. [primary] es la que se muestra y se abre por defecto; [members] son todas (incluida
 * [primary]), que es lo que alimenta el selector de fuente del detalle.
 */
data class LibraryGroup(
    val key: String,
    val primary: LibraryRow,
    val members: List<LibraryRow>,
) {
    /** Cuántas adquisiciones distintas hay detrás de la tarjeta (1 = no está agrupada). */
    val sourceCount: Int get() = members.size

    /** Suma de capítulos de todas las fuentes: es lo que el usuario puede ver en total. */
    val episodeCount: Int get() = members.sumOf { it.episodeCount }
}

/**
 * Agrupa la biblioteca para que una misma serie entrada desde varias fuentes (web, torrent, alfa,
 * archive) sea una sola tarjeta.
 *
 * Los `identifier` se prefijan A PROPÓSITO por fuente para que no colisionen en `items` (ver
 * [SeriesItemIds]); eso está bien para guardar, pero el home no debería mostrarlos por separado.
 * Acá se los junta SOLO para mostrar: no se toca ni se borra ninguna fila, así que el sync no se
 * entera y esto es reversible.
 */
object LibraryGrouping {

    /**
     * Llave por la que se juntan dos ítems.
     *
     * Orden de preferencia, medido contra la biblioteca real (2026-08-10):
     *  1. **Películas: nunca.** El `tmdbId` de `artwork` se resuelve buscando por título y en
     *     películas se equivoca — junta "Lego Batman" con "Batman (1966)" bajo `movie:324849`.
     *     Agruparlas sería peor que el duplicado, así que cada película es su propio grupo.
     *  2. **`tmdbId` de `artwork` con `tmdbType == "tv"`.** En series sí acierta (los 4 Naruto en
     *     `tv:46260`, DAN DA DAN en `tv:240411`) y es lo ÚNICO que cruza fuentes distintas, porque
     *     no depende del prefijo del identifier. Ranma 1989 y el remake 2024 caen en ids distintos,
     *     así que no las fusiona.
     *  3. **seriesId del identifier**, que es exacto pero solo existe en `web:series:` y
     *     `torrent:series:`.
     *  4. El propio identifier: grupo de uno, o sea lo que hace la app hoy.
     */
    fun groupKeyOf(row: LibraryRow, artwork: ArtworkEntity?): String {
        if (row.isMovie) return "item:${row.identifier}"
        val tvId = artwork?.tmdbId?.takeIf { artwork.tmdbType == "tv" }
        if (tvId != null) return "tv:$tvId"
        SeriesItemIds.seriesIdOrNull(row.identifier)?.let { return "series:$it" }
        return "item:${row.identifier}"
    }

    /**
     * Arma los grupos preservando el orden de entrada por lo más reciente de cada grupo: agrupar no
     * debe reordenar la fila del home, que ya viene ordenada por `addedAt DESC`.
     *
     * El representante es el de MÁS capítulos (con más capítulos = la adquisición más completa; es
     * la que conviene abrir), y a igualdad de capítulos, el más reciente.
     */
    fun group(rows: List<LibraryRow>, artwork: Map<String, ArtworkEntity>): List<LibraryGroup> =
        rows.groupBy { groupKeyOf(it, artwork[it.identifier]) }
            .map { (key, members) ->
                LibraryGroup(
                    key = key,
                    primary = members.maxWith(compareBy({ it.episodeCount }, { it.addedAt })),
                    members = members,
                )
            }
            .sortedByDescending { g -> g.members.maxOf { it.addedAt } }
}
