package com.arkiv.player.data

import com.arkiv.player.data.db.ArtworkEntity
import com.arkiv.player.data.db.LibraryRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

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

    /**
     * Capítulos de la adquisición más completa, NO la suma de todas: las fuentes son copias
     * alternativas de la MISMA serie, no contenido disjunto, así que sumarlas infla el número (6
     * adquisiciones de Naruto sumaban 794 ep. para una serie de ~220). El detalle de cada fuente ya
     * se ve aparte en los chips de "Fuentes"; acá no se pierde nada.
     */
    val episodeCount: Int get() = members.maxOf { it.episodeCount }

    /**
     * Capítulos nuevos desde la última vez que se abrió el detalle, para el badge de la tarjeta.
     *
     * Es el **máximo** entre fuentes por el mismo motivo que [episodeCount] y no por comodidad: las
     * adquisiciones son copias alternativas de la MISMA serie, no contenido disjunto. Si el mismo
     * capítulo aparece en la copia de archive y en la web, es UN capítulo nuevo, no dos — sumarlas
     * mentiría igual que sumaba 794 episodios para una serie de 220.
     */
    val nuevos: Int get() = members.maxOf {
        com.arkiv.player.data.nuevos.ContadorDeNuevos.cuantos(it.episodeCount, it.episodiosVistosEnLista)
    }
}

/**
 * Groups the library so a series entered from several sources (Magis, Ditu, or the legacy
 * web/torrent/archive sources from before this branch's pruning) becomes a single card.
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
     *  3. **`tmdbId` del propio ítem**, que llena el gateway canonizando el título contra TMDB.
     *     Va después del arte porque el arte ya estaba probado; rescata las filas cuyo título no
     *     existe en TMDB y que por eso el aparato nunca pudo resolver solo.
     *  4. **seriesId del identifier**, que es exacto pero solo existe en `web:series:` y
     *     `torrent:series:`.
     *  5. El propio identifier: grupo de uno, o sea lo que hace la app hoy.
     */
    fun groupKeyOf(row: LibraryRow, artwork: ArtworkEntity?): String {
        // El tipo canónico manda sobre la heurística de "1 video = película": un capítulo suelto
        // tiene un solo video y sin `categoryOverride` cae como película, que es exactamente lo
        // que impedía juntarlo con su serie. Medido en la base del Fire TV: las cinco filas de
        // Evangelion seguían separadas incluso teniendo ya su tmdbId. Si el gateway verificó
        // contra TMDB que la obra es una serie, es una serie.
        val canonTv = row.tmdbId?.takeIf { it > 0 && row.tipo == "tv" }
        if (row.isMovie) return canonTv?.let { "tv:$it" } ?: "item:${row.identifier}"
        val tvId = artwork?.tmdbId?.takeIf { artwork.tmdbType == "tv" }
        if (tvId != null) return "tv:$tvId"
        // Respaldo: el `tmdbId` que el gateway le puso al ÍTEM canonizando su título. Va DESPUÉS
        // del arte a propósito -- lo que hoy agrupa tiene que seguir agrupando igual -- y rescata
        // justo lo que el arte no puede: un capítulo suelto guardado con el título del capítulo no
        // le pega a ninguna búsqueda de TMDB, así que `ensureArtwork` nunca le resuelve nada y
        // cada fila queda en su propia tarjeta. El `> 0` no es paranoia: el campo numérico de
        // PocketBase nace en 0, y agrupar por "tv:0" juntaría toda la biblioteca sin canonizar en
        // una sola tarjeta.
        // `tipo != "movie"`: lo que no sabemos sigue agrupando como hasta ahora, pero un id que
        // el gateway marcó como PELÍCULA no junta nada -- misma razón por la que las películas
        // nunca se agrupan.
        row.tmdbId?.takeIf { it > 0 && row.tipo != "movie" }?.let { return "tv:$it" }
        SeriesItemIds.seriesIdOrNull(row.identifier)?.let { return "series:$it" }
        return "item:${row.identifier}"
    }

    /**
     * Arma los grupos preservando el orden de entrada por lo más reciente de cada grupo: agrupar no
     * debe reordenar la lista, que ya viene ordenada por `addedAt DESC`. Ese orden es el de
     * DESEMPATE: quien muestra los grupos los reordena después por lo último visto (ver
     * `ArkivRepository.observeLibraryGroups` y `biblioteca.LibraryOrder`), y como el orden de
     * Kotlin es estable, dos grupos con la misma recencia conservan este.
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

    /**
     * Miembros a mostrar para una llave de ruta ([TvDetailScreen]/`DetailScreen`), que puede
     * haber dejado de ser la llave de un grupo vivo: `ensureArtwork` corre en segundo plano y
     * puede resolverle un `tmdbId` de tv al ítem MIENTRAS el detalle está abierto, momento en el
     * que su grupo pasa de `item:<identifier>` a `tv:<tmdbId>` y la llave vieja deja de existir.
     *
     * Cuatro casos, en orden:
     *  1. [groupKey] sigue siendo la llave de un grupo: sus miembros, más completo primero.
     *  2. No, pero es una llave `item:<identifier>` y ESE identifier ahora vive dentro de OTRO
     *     grupo: los miembros de ESE grupo. Sin este paso, el detalle se queda apuntando a una
     *     llave fantasma y desaparece (pantalla en negro) apenas el arte resuelve.
     *  3. No, pero es una llave `series:<seriesId>` y ESE seriesId ahora vive dentro de OTRO grupo
     *     (mismo problema que el paso 2, pero para series): un `series:<X>` NUNCA es igual a un
     *     identifier (que van prefijados `web:series:`/`torrent:series:`, ver [SeriesItemIds]), así
     *     que sin este paso el 4 jamás la encuentra y el detalle queda en pantalla negra apenas el
     *     arte le resuelve un `tmdbId` de tv al ítem y su grupo pasa de `series:<id>` a `tv:<id>`.
     *  4. Ninguna de las anteriores: la fila suelta con ese identifier (o vacío si ni eso existe).
     *
     * A PROPÓSITO los pasos 2 y 3 solo aplican a llaves `item:`/`series:`, no a un identifier
     * crudo. "Continuar viendo", el menú de mantener presionado y el detalle del teléfono navegan
     * con el identifier crudo de una fila puntual asumiendo "esta fila exacta"; si también
     * siguieran el rastro al grupo, un identifier que resultó formar parte de un grupo (p. ej.
     * porque otra fuente de la misma serie ya tenía tmdbId) les cambiaría de ítem sin que el
     * usuario lo haya pedido. `series:<seriesId>` en cambio SOLO llega desde `TvHomeScreen`
     * navegando con la llave del grupo (`onOpenItem(it.key)`), un llamador que sí quiere seguir
     * el rastro — igual que `item:`.
     */
    fun resolveMembers(
        groupKey: String,
        groups: List<LibraryGroup>,
        rows: List<LibraryRow>,
    ): List<LibraryRow> {
        groups.firstOrNull { it.key == groupKey }?.let {
            return it.members.sortedByDescending { m -> m.episodeCount }
        }
        if (groupKey.startsWith("item:")) {
            val identifier = groupKey.removePrefix("item:")
            groups.firstOrNull { g -> g.members.any { m -> m.identifier == identifier } }
                ?.let { return it.members.sortedByDescending { m -> m.episodeCount } }
            return rows.filter { it.identifier == identifier }
        }
        if (groupKey.startsWith("series:")) {
            val seriesId = groupKey.removePrefix("series:")
            groups.firstOrNull { g -> g.members.any { m -> SeriesItemIds.seriesIdOrNull(m.identifier) == seriesId } }
                ?.let { return it.members.sortedByDescending { m -> m.episodeCount } }
        }
        return rows.filter { it.identifier == groupKey }
    }

    /** Ventana de gracia antes de reintentar en TMDB un ítem que quedó sin match. */
    private const val ARTWORK_RETRY_WINDOW_MS = 7 * 24 * 60 * 60 * 1000L

    /**
     * Si `ensureArtwork` tiene que volver a pedirle el arte a TMDB a este ítem.
     *
     * NO, si ya tiene [ArtworkEntity.tmdbId] (resuelto) o ya tiene backdrops aunque no tenga
     * tmdbId — arte que puso OTRA fuente, como el backdrop del portal que guarda
     * `ArkivRepository.addMagisSource`. Esa fila nunca se vuelve a tocar: si se reintentara,
     * un ítem de Magis sin match en TMDB perdería su backdrop real (pisado por un `"[]"`) cada
     * 7 días, para siempre, sin que el usuario hiciera nada.
     *
     * Tampoco si está vacía (sin tmdbId ni backdrops) pero es reciente: así un título que TMDB no
     * conoce no se consulta en cada arranque. Sí, si está vacía y ya pasó la ventana: ahí vale la
     * pena reintentar por si el título sucio de antes ahora matchea (ver `cleanTitleForSearch`).
     */
    fun shouldRefetchArtwork(existing: ArtworkEntity?, now: Long): Boolean {
        if (existing == null) return true
        if (existing.tmdbId != null) return false
        if (existing.backdrops.isNotEmpty()) return false
        return now - existing.fetchedAt >= ARTWORK_RETRY_WINDOW_MS
    }

    /**
     * Los dos flows combinados. Vive acá (y no en el repositorio) para poder testearlo sin Room:
     * el repositorio solo lo cablea con sus DAOs.
     */
    fun groupsFlow(
        rows: Flow<List<LibraryRow>>,
        artwork: Flow<Map<String, ArtworkEntity>>,
    ): Flow<List<LibraryGroup>> =
        combine(rows, artwork) { r, a -> group(r, a) }
}
