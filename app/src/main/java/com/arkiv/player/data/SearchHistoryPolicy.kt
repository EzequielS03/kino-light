package com.arkiv.player.data

/**
 * Un título que se abrió desde el buscador, guardado para poder volver a él sin buscarlo de nuevo.
 *
 * No se persiste el `TitleCard` de la UI: arrastra `overview` y `backdropUrl`, que el hero de la
 * fase RESULTS vuelve a pedir a TMDB/AniList igual. Ver `TitleCard.toRecent()` en CardContext.kt.
 */
data class RecentTitle(
    val kind: String,
    val tmdbId: Int?,
    val anilistId: Long?,
    val title: String,
    val posterUrl: String,
    val year: String,
)

/**
 * Lo único del historial que SQLite no resuelve solo.
 *
 * Antes acá vivían el orden, el tope y el dedupe; ahora los hace la consulta
 * (`ORDER BY atMs DESC LIMIT`) y la PK con REPLACE. Ver [SearchHistoryRepo].
 */
object SearchHistoryPolicy {

    const val MAX_QUERIES = 10
    const val MAX_TITLES = 12

    /** Recorta el texto buscado. Devuelve null si no queda nada que valga la pena guardar. */
    fun normalizeQuery(texto: String): String? = texto.trim().ifEmpty { null }

    /**
     * Id de identidad de un título, que es la PK de `recent_titles`.
     *
     * Por id de la fuente, no por nombre: hay series distintas que se llaman igual, y el mismo
     * número puede ser una peli en TMDB y otra cosa en AniList — por eso el `kind` va adelante.
     * Sin ningún id (no debería pasar, pero una fila vieja puede traerlo) cae al nombre en
     * minúsculas, que es mejor que dar todo por distinto y llenar la lista de repetidos.
     */
    fun titleId(kind: String, tmdbId: Int?, anilistId: Long?, title: String): String = when {
        tmdbId != null -> "$kind:tmdb-$tmdbId"
        anilistId != null -> "$kind:anilist-$anilistId"
        else -> "$kind:n-${title.trim().lowercase()}"
    }

    fun titleId(t: RecentTitle): String = titleId(t.kind, t.tmdbId, t.anilistId, t.title)
}
