package com.arkiv.player.data

import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.catalog.TmdbItem
import com.arkiv.player.data.catalog.web.WebTmdbMatcher
import com.arkiv.player.data.db.ArkivDatabase
import com.arkiv.player.data.db.ArtworkEntity
import com.arkiv.player.data.db.ContinueRow
import com.arkiv.player.data.db.EpisodeStillEntity
import com.arkiv.player.data.db.LibraryRow
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.model.ArchiveItem
import com.arkiv.player.data.model.Episode
import com.arkiv.player.data.model.EpisodeNumbering
import com.arkiv.player.miniaturas.AlmacenDeFrames
import com.arkiv.player.miniaturas.DestructorDeFrames
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray

/** Fuente para reproducir un episodio torrent: magnet (no bloqueante) o bytes de .torrent. */
sealed interface EpisodeTorrent {
    data class Magnet(val uri: String) : EpisodeTorrent
    data class Bytes(val data: ByteArray, val fileIndex: Int) : EpisodeTorrent
}

/** Datos para buscar subtítulos de lo que se está reproduciendo. */
data class SubtitleContext(val imdbId: String?, val title: String, val season: Int?, val episode: Int?)

/**
 * Mínimo de reproducción para entrar en "Continuar viendo". Por debajo de esto fue abrir y
 * cerrar (o una pasada rápida por el capítulo equivocado), no algo que estés viendo de verdad.
 */
private const val CONTINUE_WATCHING_MIN_MS = 2 * 60 * 1000L

/** Detalle de un ítem con el progreso de reproducción de cada episodio. */
data class ItemDetail(
    val identifier: String,
    val title: String,
    val description: String?,
    val thumbnailUrl: String,
    val episodes: List<Episode>,
    val progress: Map<String, PlaybackEntity>,
    val isTorrent: Boolean = false,
) {
    /**
     * Último episodio **tocado** y sin terminar (el "capítulo en el que voy"), o null si no hay.
     *
     * Alcanza con que exista la fila de `playback`: NO se exige `positionMs > 0` porque
     * `PlayerViewModel.saveProgress` no escribe nada hasta conocer la duración, y en Magis la sonda
     * de duración puede tardar (stream TS). Sin esto, darle play al E5 y salir a los tres segundos
     * dejaba el detalle diciendo "vas en el E1". La fila "Continuar viendo" del home sí filtra por
     * posición (`observeContinueWatching`), que es lo que evita que se llene de ruido.
     *
     * Consecuencia aceptada de NO tener ese piso acá: tocar un capítulo por error (o por
     * curiosidad) y salir a los dos segundos lo convierte en "por dónde voy" aunque tuvieras
     * mucho más progreso en otro -- el detalle lo dice, el botón "Reproducir" lo ofrece, el
     * carrusel lo resalta y las dos pantallas de detalle hacen auto-scroll hasta ahí. Es a
     * propósito: es la misma razón por la que "Continuar viendo" SÍ filtra por posición y esto no
     * (ver el párrafo de arriba), y no hay forma de distinguir "toque por error" de "toque real"
     * sin ese piso. No es un bug para "arreglar" con un mínimo de segundos acá -- eso rompería el
     * caso que este getter existe para resolver.
     */
    val inProgressEpisode: Episode?
        get() = episodes
            .mapNotNull { ep -> progress[ep.id]?.let { ep to it } }
            .filter { !it.second.watched }
            .maxByOrNull { it.second.lastPlayedAt }
            ?.first

    /**
     * Episodio para el botón "Reproducir": el que estás viendo, o el que sigue al último que
     * terminaste.
     *
     * El fallback NO es "el primero sin ver" a secas: con la temporada entera guardada de una sola
     * vez (ver `addMagisSeason`), tocar y terminar el E5 sin haber tocado ningún otro capítulo deja
     * E1-E4 y E6-E20 igual de "sin ver" que el E6, así que "el primero sin ver" por orden caía
     * siempre en el E1 en vez de seguir donde ibas.
     *
     * Tampoco alcanza con "el visto más adelantado EN LA LISTA" (por posición): ver el E10 suelto
     * por curiosidad y después arrancar en orden y terminar E1-E3 dejaría "Reproducir" ofreciendo
     * el E11, saltándose E4-E9. La regla es por RECENCIA, igual que [inProgressEpisode]: se busca
     * el capítulo terminado más reciente por `lastPlayedAt` y se ofrece el que le sigue en la
     * lista. Consecuencia asumida (no es un bug, no "arreglar" esto): si terminaste toda la serie
     * y después revisitaste el E1, "Reproducir" pasa a ofrecer el E2 -- es lo que espera alguien
     * que está reviendo. Si todavía no se vio nada, cae al primero sin ver (el primero a secas);
     * si se vio todo (no hay "siguiente" tras el último terminado), vuelve a empezar por el primero.
     */
    val resumeEpisode: Episode?
        get() = inProgressEpisode
            ?: episodes.withIndex()
                .filter { (_, ep) -> progress[ep.id]?.watched == true }
                .maxByOrNull { (_, ep) -> progress.getValue(ep.id).lastPlayedAt }
                ?.let { (idx, _) -> episodes.getOrNull(idx + 1) }
            ?: episodes.firstOrNull { progress[it.id]?.watched != true }
            ?: episodes.firstOrNull()
}

/** Punto único de acceso a los datos: red (archive.org) + persistencia (Room). */
class ArkivRepository(
    private val db: ArkivDatabase,
    private val api: ArchiveApi,
    private val tmdbApi: TmdbApi? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Dónde vive el JPEG de cada capítulo, para resolver `ContinueRow.framePath` desde disco (ver
     * `observeContinueWatching`). Nullable con default para no romper otros call sites: sin
     * almacén, `framePath` queda simplemente en null y las pantallas caen a sus respaldos de
     * siempre.
     */
    private val almacenDeFrames: AlmacenDeFrames? = null,
    /**
     * El único destructor de frames del proceso: `AppGraph` le pasa acá EL MISMO que le da a
     * `CloudSyncManager` y a `LibraryWiper`. El default está para los call sites que arman un
     * repositorio suelto (pruebas, herramientas) y arma uno equivalente sobre las mismas dos cosas
     * — el almacén de arriba y el DAO de esta base.
     */
    private val destructorDeFrames: DestructorDeFrames =
        DestructorDeFrames(almacenDeFrames, db.episodeFrameDao()),
) {
    private val itemDao = db.itemDao()
    private val playbackDao = db.playbackDao()
    private val downloadDao = db.downloadDao()
    private val skipMarkerDao = db.skipMarkerDao()
    private val artworkDao = db.artworkDao()
    private val episodeStillDao = db.episodeStillDao()

    fun observeLibrary(): Flow<List<LibraryRow>> = itemDao.observeLibrary()

    /**
     * La biblioteca ya agrupada: una entrada por serie, no por adquisición. Ver [LibraryGrouping].
     * `observeLibrary()` sigue existiendo para quien necesite las filas crudas (la pantalla de
     * biblioteca del teléfono, el sync).
     */
    fun observeLibraryGroups(): Flow<List<LibraryGroup>> =
        LibraryGrouping.groupsFlow(observeLibrary(), observeArtwork())

    /**
     * Los ítems detrás de una llave de grupo, del más completo al menos.
     *
     * Acepta TAMBIÉN un identifier crudo: "Continuar viendo", el menú de mantener presionado y el
     * detalle del teléfono navegan con el identifier del ítem, no con una llave de grupo. Y acepta
     * una llave `item:<identifier>` que dejó de ser un grupo vivo porque su ítem se sumó a otro
     * grupo MIENTRAS el detalle estaba abierto (`ensureArtwork` resolviéndole un tmdbId de tv en
     * segundo plano): ahí sigue al ítem hasta su grupo nuevo en vez de devolver vacío. Ver
     * [LibraryGrouping.resolveMembers]. Solo devuelve vacío si de verdad no hay nada con ese
     * identifier (por ejemplo si se borró la única fuente mientras el detalle estaba abierto).
     *
     * Una sola suscripción a la biblioteca: las filas se derivan de los miembros de [groups] (que
     * ya sale de `observeLibrary()` vía [observeLibraryGroups]) en vez de volver a combinar
     * `observeLibrary()` acá aparte.
     */
    fun observeGroupMembers(groupKey: String): Flow<List<LibraryRow>> =
        observeLibraryGroups().map { groups ->
            LibraryGrouping.resolveMembers(groupKey, groups, groups.flatMap { it.members })
        }

    fun observeContinueWatching(): Flow<List<ContinueRow>> =
        playbackDao.observeContinueWatching(CONTINUE_WATCHING_MIN_MS).map { rows ->
            // Una tarjeta por ÍTEM, no por episodio: la consulta devuelve una fila por capítulo
            // a medias, así que una serie llenaba la fila con la misma carátula repetida
            // (GetBackers llegó a 9 tarjetas). Como ya viene ordenada por lastPlayedAt desc,
            // distinctBy deja el capítulo más reciente de cada serie.
            // OJO: agrupa por itemId, NO por título — el dedup por título se quitó a propósito
            // porque escondía ítems distintos que casualmente compartían nombre.
            rows.distinctBy { it.itemId }.take(20)
        }.map { filas ->
            // El framePath NO sale de la query (ver el doc del campo en ContinueRow): se resuelve
            // acá, del disco, después del dedup/take(20) de arriba para no gastar File.exists()
            // de más en filas que ni se van a mostrar. Son ~6 filas por emisión: despreciable.
            filas.map { it.copy(framePath = almacenDeFrames?.rutaSiExiste(it.episodeId)) }
        }

    /**
     * Lo ya visto, por ítem. El cruce contra los grupos de la biblioteca lo hace
     * [com.arkiv.player.data.biblioteca.VistosDeLaBiblioteca], que es la parte pura y testeada.
     */
    fun observeVistos(): Flow<List<com.arkiv.player.data.biblioteca.VistoDeItem>> =
        playbackDao.observeVistos().map { filas ->
            filas.map { com.arkiv.player.data.biblioteca.VistoDeItem(it.itemId, it.episodios, it.ultimoVistoMs) }
        }

    // --- Arte de TMDB (local, no sincronizado) -----------------------------------------------

    /** Mapa itemId -> arte resuelto de TMDB, para pintar backdrops en el home. */
    fun observeArtwork(): Flow<Map<String, ArtworkEntity>> =
        artworkDao.observeAll().map { list -> list.associateBy { it.itemId } }

    /**
     * Resuelve, para los ítems que aún no tengan arte, sus backdrops de TMDB. Secuencial y
     * best-effort: los ítems sin match quedan con una fila vacía para no re-buscarlos cada vez.
     * No hace nada si TMDB no está configurado.
     */
    suspend fun ensureArtwork(rows: List<LibraryRow>) {
        val tmdb = tmdbApi?.takeIf { it.configured } ?: return
        for (row in rows) {
            // Un arte YA resuelto (tmdbId) o que YA tiene backdrops aunque no tenga tmdbId (el
            // backdrop del portal que guarda addMagisSource) no se vuelve a pedir NUNCA: son los
            // dos casos donde ya hay algo bueno que perder. Uno vacío de las dos formas sí se
            // reintenta, pero solo si la fila es vieja: así un título que TMDB no conoce no se
            // consulta en cada arranque, y a la vez los ítems que fallaron por un título sucio
            // (ver cleanTitleForSearch) se recuperan solos tras una actualización. Ver
            // LibraryGrouping.shouldRefetchArtwork para el detalle de la regla.
            val existing = artworkDao.get(row.identifier)
            if (!LibraryGrouping.shouldRefetchArtwork(existing, clock())) continue
            val type = if (row.isMovie) "movie" else "tv"
            val match = searchTmdbMatch(tmdb, type, row.title)
            val backdrops = if (match != null) {
                runCatching { tmdb.images(type, match.id) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            artworkDao.upsert(
                ArtworkEntity(
                    itemId = row.identifier,
                    tmdbId = match?.id,
                    tmdbType = match?.let { type },
                    backdropsJson = JSONArray(backdrops).toString(),
                    fetchedAt = clock(),
                ),
            )
        }
    }

    /**
     * Busca en TMDB el título de un ítem: primero con el título limpio y, si ese no da nada, con el
     * crudo. La elección entre los resultados es de [pickTmdbMatch], NO el primero que llegue.
     *
     * null = no hubo match, y eso incluye "la red se cayó": quien lo llame decide si eso es
     * "guardar vacío" (ensureArtwork, que reintenta a los 7 días) o "no tocar nada"
     * ([repairArtworkMatches], que tiene arte bueno que perder).
     */
    private suspend fun searchTmdbMatch(tmdb: TmdbApi, type: String, title: String): TmdbItem? {
        val cleaned = cleanTitleForSearch(title)
        return runCatching { pickTmdbMatch(cleaned, tmdb.search(type, cleaned)) }.getOrNull()
            ?: runCatching { pickTmdbMatch(title, tmdb.search(type, title)) }.getOrNull()
    }

    /** Que la reparación del arte corra UNA vez por proceso: hay un HomeViewModel por pantalla. */
    private val artworkRepairRan = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Repara, de una sola pasada, el arte que se resolvió ANTES de que existiera [pickTmdbMatch].
     *
     * Hace falta porque arreglar la elección del match no repara lo ya guardado: `ensureArtwork`
     * salta cualquier fila que ya tenga `tmdbId` (ver [LibraryGrouping.shouldRefetchArtwork]), así
     * que los títulos que quedaron apuntando al hermano más popular —los tres Dragon Ball con el
     * `tmdbId` de Dragon Ball Z— se quedarían así para siempre.
     *
     * Dos cuidados, los dos por no destruir arte bueno:
     *  - Solo filas CON `tmdbId`, que son las que resolvió `ensureArtwork` buscando por título. Una
     *    fila con backdrops pero sin `tmdbId` es arte que puso el portal (`addMagisSource`) y no se
     *    toca nunca.
     *  - Si la búsqueda no devuelve nada, la fila se deja **como está**. Sin esto, una pasada con la
     *    red caída borraría el arte de toda la biblioteca de una.
     *
     * Devuelve true solo si la pasada se completó entera; false si algo falló y conviene reintentar
     * en el próximo arranque.
     */
    suspend fun repairArtworkMatches(rows: List<LibraryRow>): Boolean {
        if (!artworkRepairRan.compareAndSet(false, true)) return false
        val tmdb = tmdbApi?.takeIf { it.configured } ?: return false
        var complete = true
        for (row in rows) {
            val existing = artworkDao.get(row.identifier) ?: continue
            val stored = existing.tmdbId ?: continue
            val type = if (row.isMovie) "movie" else "tv"
            val match = searchTmdbMatch(tmdb, type, row.title)
            if (match == null) {
                complete = false
                continue
            }
            if (match.id == stored && existing.tmdbType == type) continue
            val backdrops = runCatching { tmdb.images(type, match.id) }.getOrNull()
            if (backdrops == null) {
                complete = false
                continue
            }
            artworkDao.upsert(
                ArtworkEntity(
                    itemId = row.identifier,
                    tmdbId = match.id,
                    tmdbType = type,
                    backdropsJson = JSONArray(backdrops).toString(),
                    fetchedAt = clock(),
                ),
            )
        }
        return complete
    }

    // --- Stills de capítulos (TMDB, local y no sincronizado) --------------------------------

    /** Mapa episodeId -> URL del still, para pintar la miniatura real de cada capítulo. */
    fun observeEpisodeStills(itemId: String): Flow<Map<String, String>> =
        episodeStillDao.observeForItem(itemId).map { rows ->
            rows.mapNotNull { r -> r.stillUrl?.let { r.episodeId to it } }.toMap()
        }

    /**
     * Resuelve los stills de los capítulos de una serie desde TMDB y los cachea.
     *
     * Idempotente: si todos los capítulos ya tienen fila (aunque sea con `stillUrl` null porque
     * TMDB no tenía imagen) no vuelve a pedir nada. Sirve igual para series y anime — en TMDB
     * ambos son `tv`, que es lo que ya resuelve [ensureArtwork].
     *
     * **No es dueña de la tabla**: Magis escribe las mismas filas al guardar la temporada, con lo
     * que el gateway ya cruzó contra TMDB. Por eso esta función nunca pisa una fila entera — mezcla
     * campo por campo ([MezclaDeStills]) y no marca como "ya preguntado" lo que no se pudo
     * preguntar. Sin eso, abrir el detalle con la red caída borraba lo que Magis había guardado bien
     * y no se reintentaba nunca más.
     */
    suspend fun ensureEpisodeStills(itemId: String) {
        val tmdb = tmdbApi?.takeIf { it.configured } ?: return
        // El tmdbId del propio ítem manda sobre el de `artwork`: ese se resuelve buscando por
        // título en TMDB (una adivinanza que puede caer en otra serie), mientras que el del ítem
        // lo puso quien lo agregó desde la búsqueda, que sabía exactamente cuál era.
        val ownTmdbId = itemDao.getItem(itemId)?.tmdbId
        val tvId = ownTmdbId ?: artworkDao.get(itemId)
            ?.let { art -> art.tmdbId?.takeIf { art.tmdbType == "tv" } }
            ?: return

        val episodes = itemDao.getEpisodesOf(itemId)
        if (episodes.isEmpty()) return
        // Las filas que ya están, COMPLETAS y no solo sus ids: se usan dos veces — para el corte
        // temprano de acá abajo y para no pisar con null lo que otra fuente ya había llenado
        // (ver [MezclaDeStills]).
        val previas = episodeStillDao.forItem(itemId).associateBy { it.episodeId }
        if (previas.keys.containsAll(episodes.map { it.id })) return

        // Capítulo -> (temporada, episodio). Tres formas, de la más confiable a la menos.
        val coords: Map<String, Pair<Int, Int>> = if (episodes.all { it.season != null && it.episode != null }) {
            // El nombre del archivo declaraba la numeración (sNNeNN / NxNN): es exacta, y no se
            // desalinea aunque la copia local traiga OVAs, recaps o le falten capítulos.
            episodes.associate { it.id to (it.season!! to it.episode!!) }
        } else if (episodes.any { it.orderIndex >= 1000 }) {
            // Packs de torrent: orderIndex ya viene codificado como temporada*1000 + episodio.
            episodes.associate { it.id to (it.orderIndex / 1000 to it.orderIndex % 1000) }
        } else {
            // Archive: lista plana 1..N sin temporadas. Se aplanan las de TMDB en orden y se
            // reparte por conteo (con 25+24, el capítulo 26 cae en T2E1). Si la copia local
            // trae OVAs o recaps intercalados, este reparto se desalinea.
            val seasons = tmdb.detail("tv", tvId)?.seasons
                ?.filter { it.seasonNumber > 0 && it.episodeCount > 0 }
                ?.sortedBy { it.seasonNumber }
                .orEmpty()
            if (seasons.isEmpty()) return
            val flat = seasons.flatMap { s -> (1..s.episodeCount).map { s.seasonNumber to it } }
            episodes.sortedBy { it.orderIndex }
                .mapIndexedNotNull { i, ep -> flat.getOrNull(i)?.let { ep.id to it } }
                .toMap()
        }
        if (coords.isEmpty()) return

        // Una llamada por temporada, no por capítulo.
        val stillBySeasonEp = mutableMapOf<Pair<Int, Int>, String>()
        val titleBySeasonEp = mutableMapOf<Pair<Int, Int>, String>()
        val overviewBySeasonEp = mutableMapOf<Pair<Int, Int>, String>()
        // Temporadas cuya consulta a TMDB se cayó (red, rate-limit, 5xx). Distinto de "TMDB
        // contestó y no tenía nada": eso último SÍ se escribe, para no repreguntar por siempre.
        val fallaron = mutableSetOf<Int>()
        for (season in coords.values.map { it.first }.distinct().sorted()) {
            // Doble null: el de `seasonEpisodes` (la consulta no se pudo hacer) y el del
            // `runCatching` (excepción inesperada). Los dos son "no se pudo preguntar".
            val eps = runCatching { tmdb.seasonEpisodes(tvId, season) }.getOrNull()
            if (eps == null) {
                // Sin este corte, un fallo se escribía como fila vacía —indistinguible del "TMDB
                // contestó y no tenía nada"—, el corte temprano de arriba daba true para siempre y
                // un solo timeout dejaba esa serie sin imágenes ni nombres hasta reinstalar la app.
                fallaron += season
                continue
            }
            eps.forEach { e ->
                if (e.stillUrl.isNotBlank()) stillBySeasonEp[e.season to e.episode] = e.stillUrl
                if (e.name.isNotBlank()) titleBySeasonEp[e.season to e.episode] = e.name
                // Misma tabla que llena Magis (`MagisEntities.stillsDeTemporada`): la sinopsis por
                // capítulo no es un privilegio de una sola fuente, las dos escriben `episode_still`
                // y la UI lee un solo lugar.
                if (e.overview.isNotBlank()) overviewBySeasonEp[e.season to e.episode] = e.overview
            }
        }

        // Se escriben TODOS los capítulos, también los que no tienen still: la fila marca
        // "ya preguntado" y evita repetir la consulta en cada apertura de la serie. Con dos
        // excepciones, las dos por lo mismo —una fila escrita acá se lee como respuesta definitiva—:
        //  1. Los de una temporada que ni siquiera se pudo consultar, para que se reintente.
        //  2. Los campos que esta consulta no trajo, que conservan lo que ya hubiera guardado (típico:
        //     lo que Magis dejó al guardar la temporada). Ver [MezclaDeStills].
        val now = clock()
        episodeStillDao.upsertAll(
            episodes
                .filter { ep -> coords[ep.id]?.first?.let { it !in fallaron } ?: true }
                .map { ep ->
                    MezclaDeStills.mezclar(
                        previa = previas[ep.id],
                        nueva = EpisodeStillEntity(
                            episodeId = ep.id,
                            stillUrl = coords[ep.id]?.let { stillBySeasonEp[it] },
                            fetchedAt = now,
                            title = coords[ep.id]?.let { titleBySeasonEp[it] },
                            overview = coords[ep.id]?.let { overviewBySeasonEp[it] },
                        ),
                    )
                },
        )
    }

    /**
     * Mapa episodeId -> título del capítulo según TMDB, para mostrarlo en vez del nombre del
     * archivo ("s01e03"). Solo trae los que TMDB conocía; el resto no aparece y la UI cae al
     * nombre del archivo.
     */
    fun observeEpisodeTitles(itemId: String): Flow<Map<String, String>> =
        episodeStillDao.observeForItem(itemId).map { rows ->
            rows.mapNotNull { r -> r.title?.let { r.episodeId to it } }.toMap()
        }

    /**
     * Mapa episodeId -> sinopsis del capítulo según TMDB. La llenan tanto Magis
     * (`addMagisSeason`/`addMagisSource`) como [ensureEpisodeStills] para torrent/web/archive:
     * cualquier serie con `tmdbId` la tiene, no es un privilegio de una sola fuente.
     */
    fun observeEpisodeOverviews(itemId: String): Flow<Map<String, String>> =
        episodeStillDao.observeForItem(itemId).map { rows ->
            rows.mapNotNull { r -> r.overview?.let { r.episodeId to it } }.toMap()
        }

    fun observeDownloadRows() = downloadDao.observeDownloadRows()

    // `completedDownloadUri` se eliminó: miraba SOLO la columna `localUri` (la que llenaba el
    // DownloadManager del sistema) e ignoraba `filePath`, que es donde escriben las descargas
    // nuevas, así que devolvía null para todo lo bajado con el worker. Tampoco verificaba que el
    // archivo siguiera existiendo. Ahora hay UN solo resolvedor de "¿dónde está el archivo local?"
    // para las tres fuentes: `LocalLibrary.fileFor`, que cubre las dos columnas, chequea exists() y
    // limpia la fila si el archivo se fue.

    /**
     * Descarga metadata, arma los episodios y guarda el ítem en la biblioteca.
     *
     * [titleOverride] pisa el título que trae archive.org. Lo necesitan nuestras propias subidas:
     * ahí el ítem se llama como el hash con el que se subió (`f75163…_s01e01`), así que sin esto
     * la biblioteca mostraría ese hash en vez del nombre de la serie. El nombre bueno lo tiene el
     * mirror (ver `MirrorApiClient.libraryItem`).
     */
    suspend fun addItem(
        input: String,
        titleOverride: String? = null,
        tmdbId: Int? = null,
        descriptionOverride: String? = null,
    ): Result<ArchiveItem> {
        val identifier = IdentifierParser.extract(input)
            ?: return Result.failure(IllegalArgumentException("Pegá una URL o identificador de archive.org"))
        return try {
            val item = api.fetchItem(identifier).let { fetched ->
                fetched.copy(
                    title = titleOverride?.takeIf { it.isNotBlank() } ?: fetched.title,
                    description = descriptionOverride?.takeIf { it.isNotBlank() } ?: fetched.description,
                )
            }
            // Al re-agregar/refrescar, preservar el override manual y la fecha original.
            val existing = itemDao.getItem(identifier)
            itemDao.replaceItem(
                item = item.toItemEntity(
                    addedAt = existing?.addedAt ?: clock(),
                    categoryOverride = existing?.categoryOverride,
                    // Un refresh sin tmdbId no debe borrar el que ya estaba: se agregó desde la
                    // búsqueda una vez y ese vínculo es lo que permite titular los capítulos.
                    tmdbId = tmdbId ?: existing?.tmdbId,
                ),
                episodes = item.episodes.map { it.toEntity() },
            )
            Result.success(item)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Fija el tipo a mano: true = película, false = serie, null = detección automática. */
    suspend fun setCategory(itemId: String, isMovie: Boolean?) {
        val value = when (isMovie) {
            true -> "movie"
            false -> "series"
            null -> null
        }
        itemDao.updateCategoryOverride(itemId, value)
    }

    /** Renombra un ítem de la biblioteca (el título es solo display; no cambia su identifier). */
    suspend fun renameItem(itemId: String, title: String) {
        val clean = title.trim()
        if (clean.isBlank()) return
        itemDao.updateTitle(itemId, clean, clock())
    }

    /**
     * Vuelve a bajar la metadata de un ítem ya guardado y reemplaza su lista de episodios, que es
     * como aparecen los capítulos subidos DESPUÉS de agregarlo a la biblioteca.
     *
     * Es seguro para el progreso: los ids de episodio se derivan del nombre del archivo en
     * archive.org, así que al re-bajar salen idénticos, y `playback` no tiene foreign key hacia
     * `episodes` (borrarlos no arrastra las marcas de "voy por aquí").
     *
     * Solo aplica a ítems de archive.org: los de torrent y los `web:` no tienen metadata que
     * re-consultar ahí, y pedirla igual sería una llamada de red condenada a fallar.
     */
    /**
     * Registra que el usuario ya vio la lista de capítulos de este ítem, que es lo que apaga el
     * badge de novedades.
     *
     * Se llama DESPUÉS de refrescar, no antes: si se marcara al abrir el detalle y el refresco
     * trajera capítulos nuevos un segundo después, esos quedarían contados como "ya vistos" sin
     * que nadie los haya visto, y no aparecerían nunca como novedad.
     */
    suspend fun marcarCapitulosVistos(identifier: String) {
        val cuantos = itemDao.getEpisodesOf(identifier).count { !it.deleted }
        itemDao.marcarEpisodiosVistos(identifier, cuantos)
    }

    suspend fun refreshItem(identifier: String): Result<ArchiveItem> {
        val existing = itemDao.getItem(identifier)
        if (existing != null && existing.source != "archive") {
            return Result.failure(IllegalStateException("Solo se refrescan los ítems de archive.org"))
        }
        if (identifier.startsWith("torrent:") || identifier.startsWith("web:")) {
            return Result.failure(IllegalStateException("Solo se refrescan los ítems de archive.org"))
        }
        // Conservar el título y la descripción que ya tiene la biblioteca. Sin esto el refresco los
        // pisa con los de archive.org, y en NUESTRAS subidas el ítem allá se llama como el hash con
        // el que se subió ("f75163…_s01e01"): la serie pasaba a mostrarse con ese hash en vez de
        // "Dragon Ball GT". Es el mismo motivo por el que addItem acepta titleOverride.
        // También respeta el renombre manual del usuario, que si no se perdía en cada refresco.
        return addItem(
            identifier,
            titleOverride = existing?.title,
            descriptionOverride = existing?.description,
        )
    }

    /**
     * Guarda un torrent en la biblioteca (solo metadata; el video se streamea al ver).
     * identifier = "torrent:<infohash>"; cada video de [videoFiles] es un episodio con su índice.
     */
    suspend fun addTorrent(
        name: String,
        infoHashHex: String,
        infoBytes: ByteArray,
        videoFiles: List<com.arkiv.player.torrent.TorrentFile>,
        thumbnailUrl: String = "",
        description: String? = null,
    ): String {
        val id = "torrent:$infoHashHex"
        val item = com.arkiv.player.data.db.ItemEntity(
            identifier = id,
            title = name.ifBlank { "Torrent" },
            description = description,
            thumbnailUrl = thumbnailUrl,
            addedAt = clock(),
            source = "torrent",
            torrentData = android.util.Base64.encodeToString(infoBytes, android.util.Base64.NO_WRAP),
        )
        val episodes = videoFiles.mapIndexed { order, f ->
            com.arkiv.player.data.db.EpisodeEntity(
                id = "$id::${f.index}",
                itemId = id,
                section = "",
                displayName = MetadataParser.cleanName(f.name),
                orderIndex = order,
                durationSeconds = 0.0,
                thumbPath = null,
                originalPath = null,
                originalFormat = null,
                originalSize = f.sizeBytes,
                derivativePath = null,
                derivativeFormat = null,
                derivativeSize = 0,
                torrentFileIndex = f.index,
            )
        }
        itemDao.replaceItem(item, episodes)
        return id
    }

    /**
     * Guarda un pack (temporada/serie completa) como UNA entrada propia de biblioteca con N episodios
     * (uno por archivo de video, cada uno con su torrentFileIndex). El título llega ya final (renombrable
     * por el usuario). Un solo .torrent para todo el pack (guardado en el ítem). Devuelve el itemId.
     */
    suspend fun savePackAsSeries(
        title: String,
        posterUrl: String,
        description: String?,
        infoHashHex: String,
        infoBytes: ByteArray,
        files: List<com.arkiv.player.data.catalog.PackFileRow>,
    ): String {
        val b64 = android.util.Base64.encodeToString(infoBytes, android.util.Base64.NO_WRAP)
        val (item, episodes) = PackEntities.build(title, posterUrl, description, infoHashHex, b64, files, clock())
        itemDao.replaceItem(item, episodes)
        return item.identifier
    }

    /**
     * Agrega un capítulo de anime como episodio de UNA serie (id estable por show de AniList),
     * de modo que One Piece 1158, 1159, … queden bajo el mismo ítem "serie" y no como películas
     * sueltas. Cada capítulo es su propio torrent, así que guarda el .torrent en el episodio.
     * Devuelve el id del episodio a reproducir.
     */
    suspend fun addAnimeEpisode(
        anilistId: Long,
        showTitle: String,
        posterUrl: String,
        episodeName: String,
        infoHashHex: String,
        infoBytes: ByteArray,
        fileIndex: Int,
        fileSizeBytes: Long,
    ): String {
        val itemId = "torrent:anime:$anilistId"
        val episodeId = "$itemId::$infoHashHex"
        val existing = itemDao.getItem(itemId)
        val item = com.arkiv.player.data.db.ItemEntity(
            identifier = itemId,
            title = showTitle.ifBlank { "Anime" },
            description = null,
            thumbnailUrl = posterUrl,
            addedAt = existing?.addedAt ?: clock(),
            // Forzar categoría "serie" aunque por ahora tenga un solo capítulo.
            categoryOverride = "series",
            source = "torrent",
            torrentData = null,
        )
        itemDao.upsertItem(item)
        val siblings = itemDao.getEpisodesOf(itemId)
        val order = siblings.firstOrNull { it.id == episodeId }?.orderIndex ?: siblings.size
        val episode = com.arkiv.player.data.db.EpisodeEntity(
            id = episodeId,
            itemId = itemId,
            section = "",
            displayName = MetadataParser.cleanName(episodeName),
            orderIndex = order,
            durationSeconds = 0.0,
            thumbPath = null,
            originalPath = null,
            originalFormat = null,
            originalSize = fileSizeBytes,
            derivativePath = null,
            derivativeFormat = null,
            derivativeSize = 0,
            torrentFileIndex = fileIndex,
            torrentData = android.util.Base64.encodeToString(infoBytes, android.util.Base64.NO_WRAP),
        )
        itemDao.upsertEpisodes(listOf(episode))
        return episodeId
    }

    /**
     * Agrega un capítulo de una serie del catálogo (Cinemeta) como episodio bajo UN ítem serie
     * estable (id por IMDb), ordenado por temporada/episodio. Cada capítulo trae su propio
     * .torrent. Para películas usar [addTorrent]. Devuelve el id del episodio a reproducir.
     */
    suspend fun addSeriesEpisode(
        seriesId: String,        // ej "tt0388629"
        showTitle: String,
        posterUrl: String,
        season: Int,
        episode: Int,
        episodeName: String,
        infoHashHex: String,
        infoBytes: ByteArray,
        fileIndex: Int,
        fileSizeBytes: Long,
        description: String? = null,
    ): String {
        val itemId = SeriesItemIds.TORRENT_SERIES_PREFIX + seriesId
        val episodeId = "$itemId::$infoHashHex"
        val existing = itemDao.getItem(itemId)
        val item = com.arkiv.player.data.db.ItemEntity(
            identifier = itemId,
            title = showTitle.ifBlank { "Serie" },
            description = description,
            thumbnailUrl = posterUrl,
            addedAt = existing?.addedAt ?: clock(),
            categoryOverride = "series",
            source = "torrent",
            torrentData = null,
        )
        itemDao.upsertItem(item)
        val label = com.arkiv.player.data.SeriesEpisodeLabel.format(showTitle, season, episode, episodeName)
        val ep = com.arkiv.player.data.db.EpisodeEntity(
            id = episodeId,
            itemId = itemId,
            section = "Temporada $season",
            displayName = label,
            orderIndex = season * 1000 + episode,
            durationSeconds = 0.0,
            thumbPath = null,
            originalPath = null,
            originalFormat = null,
            originalSize = fileSizeBytes,
            derivativePath = null,
            derivativeFormat = null,
            derivativeSize = 0,
            torrentFileIndex = fileIndex,
            torrentData = android.util.Base64.encodeToString(infoBytes, android.util.Base64.NO_WRAP),
        )
        itemDao.upsertEpisodes(listOf(ep))
        return episodeId
    }

    /** Bytes del .torrent guardado (para re-streamear un ítem torrent). */
    suspend fun torrentDataOf(itemId: String): ByteArray? {
        val data = itemDao.getItem(itemId)?.torrentData ?: return null
        if (data.startsWith("magnet:")) return null
        return runCatching { android.util.Base64.decode(data, android.util.Base64.NO_WRAP) }.getOrNull()
    }

    /**
     * Fuente para reproducir un episodio torrent: puede ser un **magnet** (streaming no bloqueante,
     * elige el video más grande) o los **bytes de un .torrent** con su índice de archivo (packs/
     * series). El dato guardado puede estar en el episodio o en el ítem.
     */
    suspend fun torrentSourceForEpisode(episodeId: String): EpisodeTorrent? {
        val ep = itemDao.getEpisode(episodeId) ?: return null
        val raw = ep.torrentData ?: itemDao.getItem(ep.itemId)?.torrentData ?: return null
        if (raw.startsWith("magnet:")) return EpisodeTorrent.Magnet(raw)
        val fileIndex = ep.torrentFileIndex ?: return null
        val bytes = runCatching { android.util.Base64.decode(raw, android.util.Base64.NO_WRAP) }.getOrNull()
            ?: return null
        return EpisodeTorrent.Bytes(bytes, fileIndex)
    }

    /** Guarda una PELÍCULA torrent como magnet (streaming no bloqueante). Devuelve el episodeId. */
    suspend fun addTorrentMagnet(name: String, magnet: String, thumbnailUrl: String = "", description: String? = null): String? {
        val hash = Regex("xt=urn:btih:([a-zA-Z0-9]{32,40})").find(magnet)?.groupValues?.get(1)?.lowercase()
            ?: return null
        val id = "torrent:$hash"
        val existing = itemDao.getItem(id)
        val item = com.arkiv.player.data.db.ItemEntity(
            identifier = id,
            title = name.ifBlank { "Torrent" },
            description = description,
            thumbnailUrl = thumbnailUrl,
            addedAt = existing?.addedAt ?: clock(),
            source = "torrent",
            torrentData = magnet, // magnet en vez de bytes
        )
        val ep = com.arkiv.player.data.db.EpisodeEntity(
            id = "$id::0", itemId = id, section = "", displayName = MetadataParser.cleanName(name),
            orderIndex = 0, durationSeconds = 0.0, thumbPath = null, originalPath = null,
            originalFormat = null, originalSize = 0, derivativePath = null, derivativeFormat = null,
            derivativeSize = 0, torrentFileIndex = null, torrentData = null,
        )
        itemDao.replaceItem(item, listOf(ep))
        return ep.id
    }

    /** Guarda un capítulo de serie del catálogo como magnet (streaming no bloqueante). */
    suspend fun addSeriesEpisodeMagnet(
        seriesId: String, showTitle: String, posterUrl: String,
        season: Int, episode: Int, episodeName: String, magnet: String,
        description: String? = null,
    ): String? {
        val hash = Regex("xt=urn:btih:([a-zA-Z0-9]{32,40})").find(magnet)?.groupValues?.get(1)?.lowercase()
            ?: return null
        val itemId = SeriesItemIds.TORRENT_SERIES_PREFIX + seriesId
        val episodeId = "$itemId::$hash"
        val existing = itemDao.getItem(itemId)
        val item = com.arkiv.player.data.db.ItemEntity(
            identifier = itemId, title = showTitle.ifBlank { "Serie" }, description = description,
            thumbnailUrl = posterUrl, addedAt = existing?.addedAt ?: clock(),
            categoryOverride = "series", source = "torrent", torrentData = null,
        )
        itemDao.upsertItem(item)
        val siblings = itemDao.getEpisodesOf(itemId)
        val order = siblings.firstOrNull { it.id == episodeId }?.orderIndex ?: (season * 1000 + episode)
        val ep = com.arkiv.player.data.db.EpisodeEntity(
            id = episodeId, itemId = itemId, section = "Temporada $season",
            displayName = com.arkiv.player.data.SeriesEpisodeLabel.format(showTitle, season, episode, episodeName),
            orderIndex = order, durationSeconds = 0.0, thumbPath = null, originalPath = null,
            originalFormat = null, originalSize = 0, derivativePath = null, derivativeFormat = null,
            derivativeSize = 0, torrentFileIndex = null, torrentData = magnet,
        )
        itemDao.upsertEpisodes(listOf(ep))
        return episodeId
    }

    /**
     * Guarda una PELÍCULA web (la pageUrl se resuelve al reproducir vía el resolver de blog). La
     * pageUrl se guarda en torrentData (payload genérico de fuente), con id prefijado `web:` y
     * source="web" — así no hace falta migración de Room. Devuelve el episodeId.
     */
    suspend fun addWebSource(pageUrl: String, title: String, posterUrl: String = ""): String? {
        val id = "web:" + pageUrl.hashCode().toUInt().toString(16)   // id estable por pageUrl
        val existing = itemDao.getItem(id)
        val item = com.arkiv.player.data.db.ItemEntity(
            identifier = id, title = title.ifBlank { "Web" }, description = null,
            thumbnailUrl = posterUrl, addedAt = existing?.addedAt ?: clock(),
            source = "web", torrentData = pageUrl,
        )
        val ep = com.arkiv.player.data.db.EpisodeEntity(
            id = "$id::0", itemId = id, section = "", displayName = MetadataParser.cleanName(title),
            orderIndex = 0, durationSeconds = 0.0, thumbPath = null, originalPath = null,
            originalFormat = null, originalSize = 0, derivativePath = null, derivativeFormat = null,
            derivativeSize = 0, torrentFileIndex = null, torrentData = pageUrl,
        )
        itemDao.replaceItem(item, listOf(ep))
        return ep.id
    }

    /** Guarda un capítulo de serie web (la pageUrl del capítulo se resuelve al reproducir). */
    suspend fun addWebSeriesEpisode(
        seriesId: String, showTitle: String, posterUrl: String,
        season: Int, episode: Int, episodeName: String, pageUrl: String,
    ): String? {
        val itemId = SeriesItemIds.WEB_SERIES_PREFIX + seriesId
        val episodeId = "$itemId::${pageUrl.hashCode().toUInt().toString(16)}"
        val existing = itemDao.getItem(itemId)
        val item = com.arkiv.player.data.db.ItemEntity(
            identifier = itemId, title = showTitle.ifBlank { "Serie" }, description = null,
            thumbnailUrl = posterUrl, addedAt = existing?.addedAt ?: clock(),
            categoryOverride = "series", source = "web", torrentData = null,
        )
        itemDao.upsertItem(item)
        val siblings = itemDao.getEpisodesOf(itemId)
        val order = siblings.firstOrNull { it.id == episodeId }?.orderIndex ?: (season * 1000 + episode)
        val ep = com.arkiv.player.data.db.EpisodeEntity(
            id = episodeId, itemId = itemId, section = "Temporada $season",
            displayName = "T$season · E$episode" + if (episodeName.isNotBlank()) "  $episodeName" else "",
            orderIndex = order, durationSeconds = 0.0, thumbPath = null, originalPath = null,
            originalFormat = null, originalSize = 0, derivativePath = null, derivativeFormat = null,
            derivativeSize = 0, torrentFileIndex = null, torrentData = pageUrl,
        )
        itemDao.upsertEpisodes(listOf(ep))
        return episodeId
    }

    /**
     * Guarda un resultado de Magis para poder reproducirlo y reanudarlo.
     *
     * El id se deriva del `contentId` del portal, NO del ref: el ref se re-emite en cada búsqueda y
     * un id derivado de él perdería la marca de "voy por aquí" cada vez. El ref se guarda aparte
     * (mismo campo donde web guarda su `pageUrl`) y se refresca al volver a encontrarlo.
     *
     * [episode] > 0 = capítulo de serie: va como episodio DENTRO del ítem de la temporada y lo
     * marca como serie desde el primero (ver [MagisEntities] para el porqué). 0 = película, que es
     * el camino de siempre y reemplaza el ítem entero.
     *
     * [seriesRef] es el ref de la TEMPORADA (el que sirve para pedirle al portal la lista de
     * capítulos), distinto del [ref] del capítulo que se va a reproducir.
     *
     * [season], [tmdbId], [still], [tmdbTitle] y [overview] son lo que trae `GatewaySerie`/
     * `GatewayEpisode` cuando el llamador los tiene a mano (hoy, `BuscadorDeCapitulos.revisarMagis`
     * al agregar un capítulo nuevo en background): un capítulo que sale así queda enriquecido igual
     * que si se hubiera tocado a mano, sin que nadie tenga que abrir la temporada. Todos opcionales
     * para los demás llamadores, que no los conocen.
     */
    suspend fun addMagisSource(
        ref: String,
        contentId: String,
        title: String,
        episode: Int = 0,
        posterUrl: String = "",
        backdropUrl: String = "",
        episodeTitle: String = "",
        seriesRef: String = "",
        season: Int? = null,
        tmdbId: Int? = null,
        still: String? = null,
        tmdbTitle: String? = null,
        overview: String? = null,
    ): String? {
        if (ref.isBlank() || contentId.isBlank()) return null
        val id = MagisEntities.itemIdDe(contentId)
        val existing = itemDao.getItem(id)
        val (item, ep) = MagisEntities.build(
            contentId = contentId, ref = ref, title = title, episode = episode,
            episodeTitle = episodeTitle, posterUrl = posterUrl, ahora = clock(),
            seriesRef = seriesRef, existente = existing, season = season, tmdbId = tmdbId,
        )
        if (episode > 0) {
            // upsert y NO replaceItem: los capítulos que ya estaban guardados de esta temporada no
            // se pueden borrar para meter el nuevo.
            itemDao.upsertItem(item)
            itemDao.upsertEpisodes(listOf(ep))
            barrerItemLegacyDeCapitulo(contentId, episode)
            // Reusa `stillsDeTemporada` (mismo filtro "trae algo" y mismo cálculo de episodeId que
            // usa `addMagisSeason` para la temporada entera) en vez de duplicar esa lógica acá para
            // un solo capítulo.
            guardarStillsDeMagis(
                id,
                MagisEntities.stillsDeTemporada(
                    id, listOf(CapituloDeTemporada(episode, episodeTitle, ref, still, tmdbTitle, overview)), clock(),
                ),
            )
        } else {
            itemDao.replaceItem(item, listOf(ep))
        }
        guardarBackdropDeMagis(id, backdropUrl)
        return ep.id
    }

    /**
     * Escribe en `episode_still` lo que Magis trajo, **sin pisar lo que ya había** ([MezclaDeStills]).
     *
     * Único punto de escritura de esa tabla desde Magis ([addMagisSource] y [addMagisSeason]), y por
     * eso no hay dos versiones de esta regla. `EpisodeStillDao.upsertAll` es un REPLACE, así que
     * mandar directo lo que devuelve `MagisEntities.stillsDeTemporada` reescribía la fila ENTERA: esa
     * lista deja en null todo campo que el gateway no resolvió, y le alcanza con que uno de los tres
     * traiga algo para incluir la fila. Concreto: se guarda la temporada, se abre el detalle y
     * [ensureEpisodeStills] completa el nombre y la sinopsis de un capítulo que el gateway no había
     * cruzado; después se marca ese capítulo y se toca "Guardar", el gateway devuelve solo el still y
     * —sin la mezcla— nombre y sinopsis se perdían en silencio. Peor todavía: como la fila seguía
     * existiendo, el corte temprano de [ensureEpisodeStills] impedía volver a llenarlos.
     *
     * La lectura de las filas previas se hace SOLO si hay algo que escribir: el caso más común es la
     * temporada sin enriquecer, y ahí esto no toca la base.
     */
    private suspend fun guardarStillsDeMagis(itemId: String, nuevas: List<EpisodeStillEntity>) {
        if (nuevas.isEmpty()) return
        val previas = episodeStillDao.forItem(itemId).associateBy { it.episodeId }
        episodeStillDao.upsertAll(MezclaDeStills.mezclarTodas(previas, nuevas))
    }

    /**
     * La imagen apaisada del portal va al mismo lugar donde el hero del Home busca la de TMDB.
     * Se escribe SOLO si Magis la trajo: una fila con backdrops —aunque tmdbId sea null, como
     * acá— ensureArtwork ya NO la vuelve a tocar (ver LibraryGrouping.shouldRefetchArtwork),
     * así que este backdrop del portal no se pisa con un "[]" cada vez que pasa la ventana de
     * reintento. Sin fila (backdropUrl vacío), TMDB la completa como siempre.
     */
    private suspend fun guardarBackdropDeMagis(itemId: String, backdropUrl: String) {
        if (backdropUrl.isBlank()) return
        artworkDao.upsert(
            com.arkiv.player.data.db.ArtworkEntity(
                itemId = itemId,
                tmdbId = null,
                tmdbType = null,
                backdropsJson = JSONArray(listOf(backdropUrl)).toString(),
                fetchedAt = clock(),
            ),
        )
    }

    /**
     * Guarda la temporada COMPLETA de Magis: es lo que corre al tocar un capítulo para verlo, con la
     * lista que la pantalla ya tenía cargada (sin red). Gemelo de [savePackAsSeries] para torrent y
     * de `addWholeWebSeries` para web — Magis era la única fuente que guardaba de a un capítulo.
     *
     * `upsert` y NO `replaceItem`: un capítulo que ya tenías guardado tiene que sobrevivir aunque el
     * portal no lo liste esta vez. Es idempotente (ids derivados del contenido), así que se puede
     * llamar en cada reproducción.
     *
     * UNA sola escritura sobre el ítem (`upsertItem`), no dos. Antes iba `upsertItem` y después un
     * UPDATE puntual (`marcarEpisodiosVistos`) para corregir el badge — dos escrituras a la misma
     * fila en la misma llamada, y si caían en el mismo segundo el trigger de sync (`SyncTriggers`)
     * recursaba hasta "too many levels of trigger recursion": la app se caía después de guardar la
     * temporada pero antes de navegar al reproductor. El trigger ya no recursa (ver `SyncTriggers`),
     * pero la segunda escritura seguía ensuciando el sync de más, así que también se saca: el total
     * post-guardado se calcula ACÁ (antes de escribir nada) y se le pasa a [MagisEntities.buildSeason]
     * ya resuelto, para que el ítem salga sellado desde el único `upsertItem`.
     *
     * El total NO es `capitulos.size`: es la UNIÓN de los capítulos que ya estaban guardados con los
     * que trae el portal (ver el porqué del `upsert` arriba), así que se calcula contra lo que ya hay
     * en la base antes de escribir la temporada nueva.
     *
     * Devuelve `número de capítulo → episodeId` para que el llamador sepa cuál reproducir sin
     * re-derivar ids a mano.
     */
    suspend fun addMagisSeason(
        contentId: String,
        title: String,
        capitulos: List<CapituloDeTemporada>,
        seriesRef: String,
        posterUrl: String = "",
        backdropUrl: String = "",
        // Null cuando TMDB no resolvió esta serie (o el gateway todavía no la mandó): `buildSeason`
        // no lo pisa contra lo que ya estaba guardado, ver su KDoc.
        tmdbId: Int? = null,
        // El `season_number` de `GatewaySerie`: `buildSeason` lo necesita para que los episodios
        // guarden la temporada real, sin la cual `ensureEpisodeStills` aplana mal (ver su KDoc).
        seasonNumber: Int? = null,
    ): Map<Int, String> {
        if (contentId.isBlank() || capitulos.isEmpty()) return emptyMap()
        val id = MagisEntities.itemIdDe(contentId)
        val existente = itemDao.getItem(id)
        // El badge es para capítulos que salieron en el portal, no para los que acabás de guardar
        // vos: se re-sella al total que va a quedar tras el upsert, que es la unión de lo que ya
        // había (undeleted) con lo que trae `capitulos` (upsert nunca los deja deleted).
        val idsExistentes = itemDao.getEpisodesOf(id).map { it.id }.toSet()
        val idsNuevos = capitulos.map { MagisEntities.episodioIdDe(id, it.number) }.toSet()
        val totalTrasGuardar = (idsExistentes + idsNuevos).size
        val episodiosVistosEnLista = com.arkiv.player.data.nuevos.ContadorDeNuevos.reSellar(
            existente?.episodiosVistosEnLista,
            totalTrasGuardar,
        )
        val (item, episodios) = MagisEntities.buildSeason(
            contentId = contentId, title = title, capitulos = capitulos, posterUrl = posterUrl,
            ahora = clock(), seriesRef = seriesRef, existente = existente,
            episodiosVistosEnLista = episodiosVistosEnLista, tmdbId = tmdbId, seasonNumber = seasonNumber,
        )
        itemDao.upsertItem(item)
        itemDao.upsertEpisodes(episodios)
        // Las tarjetas-película que dejó el esquema viejo (un ítem por capítulo), ahora que su
        // contenido vive dentro del ítem de la temporada.
        capitulos.forEach { barrerItemLegacyDeCapitulo(contentId, it.number) }
        guardarBackdropDeMagis(id, backdropUrl)
        guardarStillsDeMagis(id, MagisEntities.stillsDeTemporada(id, capitulos, clock()))
        return episodios.mapNotNull { ep -> ep.episode?.let { it to ep.id } }.toMap()
    }

    /**
     * Borra la tarjeta que dejó un capítulo guardado con el esquema viejo (un ítem por capítulo).
     *
     * Esas filas quedaron en la biblioteca como **películas** de un episodio —el bug que motivó
     * [MagisEntities]— y no hay migración de Room que las alcance. Se limpian solas al volver a
     * guardar ese mismo capítulo, que es justo cuando su contenido ya vive en el ítem de la
     * temporada y la vieja no aporta nada. Soft-delete, igual que [removeItem], para que el borrado
     * viaje por el sync y no reaparezca desde el otro dispositivo.
     *
     * El guard corta tanto si la fila no existe como si ya está con el tombstone puesto:
     * `itemDao.getItem` NO filtra `deleted` (trae la fila igual, soft-delete es un UPDATE, no un
     * DELETE), así que sin el segundo chequeo esto se llama en cada reproducción —`addMagisSeason`
     * la corre por cada capítulo de la temporada, siempre— y el borrado ya hecho se re-ejecutaría
     * para siempre: cada UPDATE redundante sobre una fila ya borrada le pisa el `updatedAt` al
     * tombstone y lo vuelve a marcar "dirty" para el sync, sin necesidad.
     */
    private suspend fun barrerItemLegacyDeCapitulo(contentId: String, episode: Int) {
        val viejo = MagisEntities.idLegacyDeCapitulo(contentId, episode)
        if (itemDao.getItem(viejo)?.deleted != false) return
        itemDao.softDeleteEpisodesOf(viejo)
        itemDao.softDeleteItem(viejo)
    }

    /** Ref opaco guardado de un episodio de Magis (para que loadMagis lo resuelva). */
    suspend fun magisRefForEpisode(episodeId: String): String? {
        val ep = itemDao.getEpisode(episodeId) ?: return null
        return ep.torrentData ?: itemDao.getItem(ep.itemId)?.torrentData
    }

    /** pageUrl guardada de un episodio web (para que loadWeb la resuelva). */
    suspend fun webSourceForEpisode(episodeId: String): String? {
        val ep = itemDao.getEpisode(episodeId) ?: return null
        return ep.torrentData ?: itemDao.getItem(ep.itemId)?.torrentData
    }

    /**
     * Encabezado del player: título del ítem + rótulo de temporada/capítulo (solo si es serie).
     * El rótulo se PARSEA, no es el displayName crudo: en la base real esos nombres traen desde
     * "s01e01" hasta la sinopsis entera con la fecha pegada. Ver EpisodeNumbering.displayLabel.
     */
    data class PlayerHeaderInfo(val itemTitle: String, val episodeLabel: String?)

    suspend fun headerInfo(episodeId: String): PlayerHeaderInfo? {
        val ep = itemDao.getEpisode(episodeId) ?: return null
        val item = itemDao.getItem(ep.itemId) ?: return null
        val count = itemDao.getEpisodesOf(ep.itemId).size
        val isMovie = when (item.categoryOverride) {
            "movie" -> true
            "series" -> false
            else -> count <= 1
        }
        val label = if (isMovie) null else EpisodeNumbering.displayLabel(ep.section, ep.displayName)
        return PlayerHeaderInfo(item.title, label)
    }

    /** Contexto para buscar subtítulos de un episodio (imdb del ítem serie, título, temporada/ep). */
    suspend fun subtitleContextForEpisode(episodeId: String): SubtitleContext? {
        val ep = itemDao.getEpisode(episodeId) ?: return null
        val item = itemDao.getItem(ep.itemId) ?: return null
        val imdb = Regex("tt\\d+").find(item.identifier)?.value
        val season = com.arkiv.player.data.model.EpisodeNumbering.seasonOf(ep.section)
        val episode = com.arkiv.player.data.model.EpisodeNumbering.episodeOf(ep.displayName)
        return SubtitleContext(imdbId = imdb, title = item.title, season = season, episode = episode)
    }

    suspend fun removeItem(identifier: String) {
        // Los capítulos se leen ANTES del soft-delete: `getEpisodesOf` filtra `deleted = 0`, así que
        // después del tombstone ya no habría de dónde sacar los ids.
        val episodios = itemDao.getEpisodesOf(identifier)
        // Soft-delete (tombstone) para que el borrado se propague por el sync en la nube.
        // Los triggers suben updatedAt; la biblioteca ya filtra deleted=0.
        itemDao.softDeleteEpisodesOf(identifier)
        itemDao.softDeleteItem(identifier)
        // Los frames sí se borran de verdad: son locales, no viajan por el sync y no los reclama
        // nadie más. Sacar la serie de la biblioteca y dejar sus JPEG en disco era dejarlos
        // huérfanos para siempre — el único otro reclamo es "capítulo visto", y a un capítulo que ya
        // no está en la biblioteca no se lo va a marcar visto nunca.
        episodios.forEach { destructorDeFrames.destruir(it.id) }
    }

    fun observeItemDetail(identifier: String): Flow<ItemDetail?> = combine(
        itemDao.observeItem(identifier),
        itemDao.observeEpisodes(identifier),
        playbackDao.observePlaybackForItem(identifier),
    ) { item, episodes, playback ->
        if (item == null) return@combine null
        ItemDetail(
            identifier = item.identifier,
            title = item.title,
            description = item.description,
            thumbnailUrl = item.thumbnailUrl,
            episodes = episodes.map { it.toEpisode() },
            progress = playback.associateBy { it.episodeId },
            isTorrent = item.source == "torrent",
        )
    }

    suspend fun getEpisode(episodeId: String): Episode? =
        itemDao.getEpisode(episodeId)?.toEpisode()

    /** Todos los episodios de un ítem, ordenados. */
    suspend fun episodesOf(itemId: String): List<Episode> =
        itemDao.getEpisodesOf(itemId).map { it.toEpisode() }

    /** Carátula del ítem al que pertenece un episodio (para el miniplayer remoto). */
    suspend fun itemThumbnailForEpisode(episodeId: String): String? {
        val ep = itemDao.getEpisode(episodeId) ?: return null
        return itemDao.getItem(ep.itemId)?.thumbnailUrl
    }

    /** Primer episodio de un ítem (para reproducir una película directo, sin lista). */
    suspend fun firstEpisodeId(itemId: String): String? =
        itemDao.getEpisodesOf(itemId).firstOrNull()?.id

    /** Devuelve el siguiente episodio de la misma sección (para autoplay). */
    suspend fun nextEpisode(episodeId: String): Episode? = neighbourEpisode(episodeId) { all, id ->
        EpisodeNavigation.nextId(all, id)
    }

    /** Devuelve el episodio anterior de la misma sección (para el control remoto). */
    suspend fun previousEpisode(episodeId: String): Episode? = neighbourEpisode(episodeId) { all, id ->
        EpisodeNavigation.prevId(all, id)
    }

    private suspend fun neighbourEpisode(
        episodeId: String,
        pick: (List<NavEpisode>, String) -> String?,
    ): Episode? {
        val current = itemDao.getEpisode(episodeId) ?: return null
        val all = itemDao.getEpisodesOf(current.itemId)
        val targetId = pick(all.map { NavEpisode(it.id, it.section) }, episodeId) ?: return null
        return all.firstOrNull { it.id == targetId }?.toEpisode()
    }

    suspend fun getPlayback(episodeId: String): PlaybackEntity? = playbackDao.get(episodeId)

    /** Progreso de reproducción de todos los episodios de un ítem (para el carrusel de capítulos). */
    suspend fun playbackForItem(itemId: String): Map<String, PlaybackEntity> =
        playbackDao.observePlaybackForItem(itemId).first().associateBy { it.episodeId }

    // --- Marcadores de opening/ending (por serie/ítem) ---

    fun observeSkipMarker(itemId: String) = skipMarkerDao.observe(itemId)

    suspend fun getSkipMarker(itemId: String) = skipMarkerDao.get(itemId)

    suspend fun saveSkipMarker(
        itemId: String,
        openingStartMs: Long?,
        openingEndMs: Long?,
        endingStartMs: Long?,
    ) {
        if (openingStartMs == null && openingEndMs == null && endingStartMs == null) {
            skipMarkerDao.delete(itemId)
        } else {
            skipMarkerDao.upsert(
                com.arkiv.player.data.db.SkipMarkerEntity(
                    itemId = itemId,
                    openingStartMs = openingStartMs,
                    openingEndMs = openingEndMs,
                    endingStartMs = endingStartMs,
                    updatedAt = clock(),
                ),
            )
        }
    }

    // --- Sincronización LAN (last-write-wins) ---

    suspend fun exportForSync() = com.arkiv.player.sync.SyncSnapshot(
        items = itemDao.getAllItems(),
        episodes = itemDao.getAllEpisodes(),
        playback = playbackDao.getAllPlayback(),
        markers = skipMarkerDao.getAll(),
    )

    /**
     * Mergea datos remotos en la DB local. Devuelve cuántas filas cambiaron.
     *
     * **Las dos puntas corren esto igual.** Antes la biblioteca era un espejo one-way: el TV
     * adoptaba la del teléfono y borraba en duro todo ítem que el teléfono no tuviera, así que lo
     * agregado EN EL TV desaparecía solo en el sync siguiente. Ahora es last-write-wins por
     * `updatedAt` con tombstones, la misma regla del sync por nube: la ausencia de una fila en el
     * snapshot del otro ya no significa "borrala", significa que todavía no se enteró. Ver
     * [com.arkiv.player.sync.SyncMerge].
     *
     * Un episodio que la otra punta borró EN DURO (no con tombstone, como hace `replaceItem` al
     * refrescar un ítem de archive.org) se queda acá: sin tombstone no hay nada que propagar. Es
     * un capítulo de más colgando, y es a propósito preferible a la alternativa de antes —borrar
     * por ausencia— que se llevaba puesta la biblioteca entera del otro lado.
     */
    suspend fun mergeFromSync(snapshot: com.arkiv.player.sync.SyncSnapshot): Int {
        var changes = 0
        val items = com.arkiv.player.sync.SyncMerge.aAplicar(
            locales = itemDao.getAllItems(), remotas = snapshot.items,
            llave = { it.identifier }, updatedAt = { it.updatedAt },
        )
        // upsert y no replaceItem: los episodios se mergean uno por uno abajo, así que borrar los
        // de este ítem para reponer los del snapshot sería justamente perder los que el otro no
        // tiene todavía.
        items.forEach { itemDao.upsertItem(it) }
        changes += items.size

        val episodes = com.arkiv.player.sync.SyncMerge.aAplicar(
            locales = itemDao.getAllEpisodes(), remotas = snapshot.episodes,
            llave = { it.id }, updatedAt = { it.updatedAt },
        )
        if (episodes.isNotEmpty()) itemDao.upsertEpisodes(episodes)
        changes += episodes.size

        // Progreso: siempre bidireccional, last-write-wins por lastPlayedAt.
        for (pb in snapshot.playback) {
            val local = playbackDao.get(pb.episodeId)
            if (local == null || pb.lastPlayedAt > local.lastPlayedAt) {
                playbackDao.upsert(pb)
                changes++
                // El progreso sincroniza HOY (esto no es la fase 2 de frames, que sincroniza el
                // JPEG en sí): si el remoto que gana el merge trae el capítulo visto —p. ej. se
                // vio en el TV y llega acá por LAN—, el frame de ESTE dispositivo tiene que morir
                // también. Si no, la tarjeta seguiría mostrando la escena de algo ya terminado en
                // el aparato que nunca lo reprodujo hasta el final.
                if (pb.watched) borrarFrameDe(pb.episodeId)
            }
        }
        for (m in snapshot.markers) {
            val local = skipMarkerDao.get(m.itemId)
            if (local == null || m.updatedAt > local.updatedAt) {
                skipMarkerDao.upsert(m)
                changes++
            }
        }
        return changes
    }

    /**
     * Sella "voy por acá" apenas arranca la reproducción, sin esperar a que se sepa la duración.
     *
     * [savePlayback] solo escribe cuando el player ya conoce `durationMs`, y en Magis eso puede
     * tardar (stream TS, sonda de hasta 20 s): hasta entonces el capítulo que estás viendo no
     * existía para el detalle. Preserva posición, duración y `watched` de lo que ya hubiera: esto
     * marca dónde estás, no reinicia el progreso ni desmarca un capítulo ya visto.
     *
     * Se salta por completo los capítulos que YA están vistos: `load()` es alcanzable también
     * para volver a mirar una escena de un capítulo terminado (desde `DetailScreen`/`EpisodeRow`
     * o el carrusel de `TvDetailScreen`), y ese re-play no puede pisar `lastPlayedAt`. Esa columna
     * alimenta dos consumidores que no distinguen "recién visto" de "reabrí algo viejo":
     * [PlaybackDao.observeVistos] (vía `VistosDeLaBiblioteca.cruzar`, ordena "Ya visto" de la
     * biblioteca) y [PlaybackDao.seriesConProgreso] (vía `SeriesPorRevisar.elegir`, decide qué
     * series barrer contra la red buscando capítulo nuevo). Sin este corte, reabrir tres segundos
     * un capítulo viejo subía esa serie al tope de "Ya visto" y la metía otra vez en el barrido de
     * red por hasta 30 días, sin que se haya visto nada nuevo. Si el usuario efectivamente vuelve a
     * mirarlo, `savePlayback` igual actualiza la fila (y recalcula `watched`) en cuanto el player
     * conoce la duración, así que no se pierde nada real.
     */
    suspend fun marcarEnCurso(episodeId: String) {
        val existente = playbackDao.get(episodeId)
        if (existente?.watched == true) return
        playbackDao.upsert(
            PlaybackEntity(
                episodeId = episodeId,
                positionMs = existente?.positionMs ?: 0L,
                durationMs = existente?.durationMs ?: 0L,
                watched = false,
                lastPlayedAt = clock(),
            ),
        )
    }

    /** Persiste posición de reproducción. Marca visto al superar el 60%. */
    suspend fun savePlayback(episodeId: String, positionMs: Long, durationMs: Long) {
        val watched = durationMs > 0 && positionMs >= durationMs * 0.6
        playbackDao.upsert(
            PlaybackEntity(
                episodeId = episodeId,
                positionMs = positionMs,
                durationMs = durationMs,
                watched = watched,
                lastPlayedAt = clock(),
            )
        )
        // Este es el camino MÁS COMÚN por el que un capítulo queda visto (el reproductor llama acá
        // cada ~5 s): si no se destruye el frame también acá, mirar un capítulo hasta el final —sin
        // tocar nunca el toggle manual de setWatched— lo dejaría vivo para siempre.
        if (watched) borrarFrameDe(episodeId)
    }

    suspend fun setWatched(episodeId: String, watched: Boolean) {
        val existing = playbackDao.get(episodeId)
        playbackDao.upsert(
            PlaybackEntity(
                episodeId = episodeId,
                positionMs = if (watched) (existing?.durationMs ?: 0L) else 0L,
                durationMs = existing?.durationMs ?: 0L,
                watched = watched,
                lastPlayedAt = clock(),
            )
        )
        // Al desmarcar (watched = false) NO se borra nada: el capítulo vuelve a estar en curso y
        // el frame que haya sigue siendo válido.
        if (watched) borrarFrameDe(episodeId)
    }

    /**
     * Destruye el frame de un capítulo que acaba de quedar visto. Delega en
     * [com.arkiv.player.miniaturas.DestructorDeFrames], que es el único sitio que sabe borrar un
     * frame (archivo + fila) — [mergeFromSync] acá abajo y
     * [com.arkiv.player.cloudsync.CloudSyncManager] llaman al mismo destructor cuando el progreso
     * que gana un merge de sync llega ya visto desde otro dispositivo, así que la lógica de borrado
     * en sí vive en un solo lugar, no acá repetida.
     *
     * Hay más de un camino por el que un capítulo pasa a `watched = true` DENTRO de este
     * repositorio: el toggle manual ([setWatched], desde el detalle) y el automático por progreso
     * ([savePlayback], al superar el 60% de la duración — el camino más común, con diferencia).
     * Los dos llaman acá; si mañana se suma un tercer camino local que marca visto, alcanza con que
     * también llame a este helper.
     *
     * Se llama incondicionalmente cada vez que `watched` da `true`, sin preguntar antes si el
     * frame existe (ver el doc de [com.arkiv.player.miniaturas.DestructorDeFrames.destruir]). En
     * particular, `savePlayback` corre cada ~5 s mientras el player está abierto, así que pasado
     * el 60% esto se repite varias veces por capítulo: el costo es despreciable y no vale la pena
     * complicar esto con lógica para evitar la repetición.
     */
    private suspend fun borrarFrameDe(episodeId: String) {
        destructorDeFrames.destruir(episodeId)
    }
}

/**
 * Título "desnudo" para buscar en TMDB.
 *
 * El sufijo " — Pack" lo pone la app al guardar un torrent que trae la serie entera; no es parte
 * del nombre y sin quitarlo TMDB no devuelve nada (verificado: los dos "Naruto — Pack" de la
 * biblioteca quedaron sin tmdbId y por eso no se agrupaban con el resto de los Naruto).
 */
/**
 * Cuál de los resultados de TMDB es el arte de este título.
 *
 * NO es `results.first()`: TMDB ordena por su score de relevancia, que le gana a la coincidencia
 * exacta cuando un título es prefijo de otro más popular. Verificado contra la API (2026-08-11):
 * `search/tv?query=Dragon Ball` devuelve "Dragon Ball Z" de primero y el "Dragon Ball" de 1986 en
 * la posición 7 de 9. Por eso los tres Dragon Ball de la biblioteca terminaron con el `tmdbId` de
 * Z: con la carátula de Z y, peor, fundidos en UNA sola tarjeta, porque [LibraryGrouping] agrupa
 * las series por `tv:<tmdbId>`.
 *
 * Primero se busca coincidencia EXACTA de título normalizado, contra el título en español Y contra
 * el original: TMDB devuelve el localizado (es-MX) pero los releases suelen venir con el original
 * en inglés ("The Simpsons" contra "Los Simpson"). Si ninguna calza se cae al primero, que es el
 * comportamiento viejo y sigue siendo la mejor apuesta cuando el título no es exacto ("Dragon Ball
 * Kai" contra el "Dragon Ball Z Kai" de TMDB).
 *
 * Un título que al normalizar queda vacío (japonés, cirílico) no matchea con nada a propósito: si
 * no, haría "coincidencia exacta" con cualquier original que también normalice a vacío, que es casi
 * todo el anime.
 */
internal fun pickTmdbMatch(query: String, results: List<TmdbItem>): TmdbItem? {
    val q = WebTmdbMatcher.normalize(query)
    if (q.isBlank()) return results.firstOrNull()
    return results.firstOrNull {
        WebTmdbMatcher.normalize(it.title) == q || WebTmdbMatcher.normalize(it.originalTitle) == q
    } ?: results.firstOrNull()
}

internal fun cleanTitleForSearch(raw: String): String {
    // Solo el SUFIJO: una raya larga en medio del título es un separador legítimo.
    var s = raw.replace(Regex("""\s*[—–-]\s*Pack\s*$""", RegexOption.IGNORE_CASE), "")
    s = s.replace('.', ' ').replace('_', ' ').replace('-', ' ').replace('—', ' ').replace('–', ' ')
    Regex("""\b(19|20)\d{2}\b""").find(s)?.let { s = s.substring(0, it.range.first) }
    val noise = Regex(
        """(?i)\b(1080p|720p|480p|2160p|4k|x264|x265|h264|h265|hevc|bluray|blu ray|brrip|bdrip|webrip|web dl|web|hdrip|dvdrip|hdtv|latino|castellano|espanol|español|dual|multi|subs?|ac3|aac|dts|yify|rarbg|proper|remux)\b""",
    )
    s = s.replace(noise, " ")
    return s.replace(Regex("""\s+"""), " ").trim().ifBlank { raw.trim() }
}
