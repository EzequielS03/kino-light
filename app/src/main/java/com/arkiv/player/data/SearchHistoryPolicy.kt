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
 * Qué entra al historial del buscador, en qué orden y cuándo cae lo viejo.
 *
 * Objeto puro a propósito: la persistencia (SharedPreferences + org.json) no se puede probar en
 * tests unitarios — con `unitTests.isReturnDefaultValues = true` las clases de Android devuelven
 * defaults — así que la decisión vive acá y [SearchHistoryStore] queda como una capa flaca de I/O.
 * Mismo patrón que UnknownLengthPolicy y TorrentSizeGate.
 */
object SearchHistoryPolicy {

    const val MAX_QUERIES = 10
    const val MAX_TITLES = 12

    /** Mete el texto al tope. Recorta, ignora vacíos y deduplica sin mirar mayúsculas. */
    fun pushQuery(actuales: List<String>, texto: String, max: Int = MAX_QUERIES): List<String> {
        val limpio = texto.trim()
        if (limpio.isEmpty()) return actuales
        val resto = actuales.filterNot { it.equals(limpio, ignoreCase = true) }
        return (listOf(limpio) + resto).take(max)
    }

    /** Mete el título al tope, deduplicando por identidad (ver [mismaIdentidad]). */
    fun pushTitle(actuales: List<RecentTitle>, nuevo: RecentTitle, max: Int = MAX_TITLES): List<RecentTitle> {
        val resto = actuales.filterNot { mismaIdentidad(it, nuevo) }
        return (listOf(nuevo) + resto).take(max)
    }

    /**
     * Si dos entradas son la misma obra. Por id, no por nombre: hay series distintas que se llaman
     * igual, y el mismo número puede ser una peli en TMDB y otra cosa en AniList — por eso el `kind`
     * también cuenta. Sin ningún id (no debería pasar, pero el JSON viejo puede traerlo) cae al
     * nombre, que es mejor que dar todo por distinto y llenar la lista de repetidos.
     */
    fun mismaIdentidad(a: RecentTitle, b: RecentTitle): Boolean {
        if (a.kind != b.kind) return false
        if (a.tmdbId == null && a.anilistId == null && b.tmdbId == null && b.anilistId == null) {
            return a.title.equals(b.title, ignoreCase = true)
        }
        return a.tmdbId == b.tmdbId && a.anilistId == b.anilistId
    }
}
