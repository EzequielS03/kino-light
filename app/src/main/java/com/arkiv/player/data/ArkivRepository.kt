package com.arkiv.player.data

import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.db.ArkivDatabase
import com.arkiv.player.data.db.ArtworkEntity
import com.arkiv.player.data.db.ContinueRow
import com.arkiv.player.data.db.EpisodeStillEntity
import com.arkiv.player.data.db.LibraryRow
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.model.ArchiveItem
import com.arkiv.player.data.model.Episode
import com.arkiv.player.data.model.EpisodeNumbering
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
    /** Último episodio empezado y sin terminar (el "capítulo en el que voy"), o null si no hay. */
    val inProgressEpisode: Episode?
        get() = episodes
            .mapNotNull { ep -> progress[ep.id]?.let { ep to it } }
            .filter { !it.second.watched && it.second.positionMs > 0 }
            .maxByOrNull { it.second.lastPlayedAt }
            ?.first

    /** Episodio para el botón "Reproducir": el último visto sin terminar, o el primero. */
    val resumeEpisode: Episode?
        get() = inProgressEpisode ?: episodes.firstOrNull()
}

/** Punto único de acceso a los datos: red (archive.org) + persistencia (Room). */
class ArkivRepository(
    private val db: ArkivDatabase,
    private val api: ArchiveApi,
    private val tmdbApi: TmdbApi? = null,
    private val clock: () -> Long = System::currentTimeMillis,
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
     * detalle del teléfono navegan con el identifier del ítem, no con una llave de grupo. Si no
     * matchea ninguna de las dos cosas devuelve vacío (por ejemplo si se borró la única fuente
     * mientras el detalle estaba abierto).
     */
    fun observeGroupMembers(groupKey: String): Flow<List<LibraryRow>> =
        combine(observeLibrary(), observeLibraryGroups()) { rows, groups ->
            groups.firstOrNull { it.key == groupKey }?.members?.sortedByDescending { it.episodeCount }
                ?: rows.filter { it.identifier == groupKey }
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
            if (artworkDao.get(row.identifier) != null) continue
            val type = if (row.isMovie) "movie" else "tv"
            val match = runCatching { tmdb.search(type, cleanTitleForSearch(row.title)).firstOrNull() }.getOrNull()
                ?: runCatching { tmdb.search(type, row.title).firstOrNull() }.getOrNull()
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
        val already = episodeStillDao.forItem(itemId).map { it.episodeId }.toSet()
        if (already.containsAll(episodes.map { it.id })) return

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
        for (season in coords.values.map { it.first }.distinct().sorted()) {
            val eps = runCatching { tmdb.seasonEpisodes(tvId, season) }.getOrNull().orEmpty()
            eps.forEach { e ->
                if (e.stillUrl.isNotBlank()) stillBySeasonEp[e.season to e.episode] = e.stillUrl
                if (e.name.isNotBlank()) titleBySeasonEp[e.season to e.episode] = e.name
            }
        }

        // Se escriben TODOS los capítulos, también los que no tienen still: la fila marca
        // "ya preguntado" y evita repetir la consulta en cada apertura de la serie.
        val now = clock()
        episodeStillDao.upsertAll(
            episodes.map { ep ->
                EpisodeStillEntity(
                    episodeId = ep.id,
                    stillUrl = coords[ep.id]?.let { stillBySeasonEp[it] },
                    fetchedAt = now,
                    title = coords[ep.id]?.let { titleBySeasonEp[it] },
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

    /** Limpia un título de ítem (a veces nombre de archivo torrent) para buscar mejor en TMDB. */
    private fun cleanTitleForSearch(raw: String): String {
        var s = raw.replace('.', ' ').replace('_', ' ').replace('-', ' ')
        // Todo lo que sigue al año (19xx/20xx) suele ser ruido del release; córtalo.
        Regex("""\b(19|20)\d{2}\b""").find(s)?.let { s = s.substring(0, it.range.first) }
        // Quita tokens típicos de torrent/calidad/idioma.
        val noise = Regex(
            """(?i)\b(1080p|720p|480p|2160p|4k|x264|x265|h264|h265|hevc|bluray|blu ray|brrip|bdrip|webrip|web dl|web|hdrip|dvdrip|hdtv|latino|castellano|espanol|español|dual|multi|subs?|ac3|aac|dts|yify|rarbg|proper|remux)\b""",
        )
        s = s.replace(noise, " ")
        return s.replace(Regex("""\s+"""), " ").trim().ifBlank { raw.trim() }
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
     */
    suspend fun addMagisSource(
        ref: String,
        contentId: String,
        title: String,
        episode: Int = 0,
        posterUrl: String = "",
        backdropUrl: String = "",
    ): String? {
        if (ref.isBlank() || contentId.isBlank()) return null
        val id = "magis:$contentId" + if (episode > 0) ":e$episode" else ""
        val existing = itemDao.getItem(id)
        val item = com.arkiv.player.data.db.ItemEntity(
            identifier = id, title = title.ifBlank { "Magis" }, description = null,
            thumbnailUrl = posterUrl, addedAt = existing?.addedAt ?: clock(),
            source = "magis", torrentData = ref,
        )
        val ep = com.arkiv.player.data.db.EpisodeEntity(
            id = "$id::0", itemId = id, section = "", displayName = MetadataParser.cleanName(title),
            orderIndex = 0, durationSeconds = 0.0, thumbPath = null, originalPath = null,
            originalFormat = null, originalSize = 0, derivativePath = null, derivativeFormat = null,
            derivativeSize = 0, torrentFileIndex = null, torrentData = ref,
        )
        itemDao.replaceItem(item, listOf(ep))
        // La imagen apaisada del portal va al mismo lugar donde el hero del Home busca la de TMDB.
        // Se escribe SOLO si Magis la trajo: una fila vacía dejaría al ítem sin arte para siempre,
        // porque ensureArtwork saltea todo ítem que ya tenga fila. Sin fila, TMDB la completa.
        if (backdropUrl.isNotBlank()) {
            artworkDao.upsert(
                com.arkiv.player.data.db.ArtworkEntity(
                    itemId = id,
                    tmdbId = null,
                    tmdbType = null,
                    backdropsJson = JSONArray(listOf(backdropUrl)).toString(),
                    fetchedAt = clock(),
                ),
            )
        }
        return ep.id
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
        // Soft-delete (tombstone) para que el borrado se propague por el sync en la nube.
        // Los triggers suben updatedAt; la biblioteca ya filtra deleted=0.
        itemDao.softDeleteEpisodesOf(identifier)
        itemDao.softDeleteItem(identifier)
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
     * @param mirrorItems si es true, la biblioteca se refleja del origen (agrega/borra/override);
     *   si es false, la biblioteca local NO se toca y solo se mergea el progreso + marcadores.
     *   El progreso y los marcadores siempre se mergean en ambos sentidos (last-write-wins).
     */
    suspend fun mergeFromSync(
        snapshot: com.arkiv.player.sync.SyncSnapshot,
        mirrorItems: Boolean = true,
    ): Int {
        var changes = 0
        if (mirrorItems) {
            val localItems = itemDao.getAllItems()
            val localItemIds = localItems.map { it.identifier }.toSet()
            val localOverride = localItems.associate { it.identifier to it.categoryOverride }
            val episodesByItem = snapshot.episodes.groupBy { it.itemId }
            for (item in snapshot.items) {
                val remoteEpisodes = episodesByItem[item.identifier] ?: emptyList()
                if (item.identifier !in localItemIds) {
                    itemDao.replaceItem(item, remoteEpisodes)
                    changes++
                    continue
                }
                // El ítem ya existe: antes solo se adoptaba la categoría y la lista de episodios
                // quedaba congelada en la del día que se agregó, así que los capítulos nuevos del
                // origen no llegaban nunca (ver EpisodeMirror).
                val localEpisodes = itemDao.getEpisodesOf(item.identifier).map { it.id }
                if (com.arkiv.player.sync.EpisodeMirror.differs(localEpisodes, remoteEpisodes.map { it.id })) {
                    itemDao.replaceItem(item, remoteEpisodes)
                    changes++
                } else if (localOverride[item.identifier] != item.categoryOverride) {
                    // La fuente (teléfono) manda: adoptar su categoría manual.
                    itemDao.updateCategoryOverride(item.identifier, item.categoryOverride)
                    changes++
                }
            }
            // Espejo one-way: borrar ítems locales que ya no existen en el origen (teléfono).
            val snapshotIds = snapshot.items.map { it.identifier }.toSet()
            for (local in localItems) {
                if (local.identifier !in snapshotIds) {
                    itemDao.deleteEpisodesOf(local.identifier)
                    itemDao.deleteItem(local.identifier)
                    changes++
                }
            }
        }
        // Progreso: siempre bidireccional, last-write-wins por lastPlayedAt.
        for (pb in snapshot.playback) {
            val local = playbackDao.get(pb.episodeId)
            if (local == null || pb.lastPlayedAt > local.lastPlayedAt) {
                playbackDao.upsert(pb)
                changes++
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
    }
}
