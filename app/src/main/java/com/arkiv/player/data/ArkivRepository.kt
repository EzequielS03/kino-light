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
import com.arkiv.player.miniaturas.BajadorDeFrames
import com.arkiv.player.miniaturas.DestructorDeFrames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
     * Gana el capítulo con REPRODUCCIÓN de verdad más reciente; los que solo tienen la fila que
     * escribe [marcarEnCurso] al abrirlos (`positionMs == 0`, sin duración todavía) quedan de
     * respaldo y solo contestan si no hay ningún otro.
     *
     * Los dos escalones hacen falta y cada uno arregla un caso distinto:
     *
     * - Sin el respaldo, darle play al E5 y salir a los tres segundos dejaba el detalle diciendo
     *   "vas en el E1": `PlayerViewModel.saveProgress` no escribe nada hasta conocer la duración, y
     *   en Magis la sonda puede tardar (stream TS), así que ahí todavía no hay posición que mirar.
     * - Sin la preferencia por el que sí tiene posición, abrir un capítulo que no llega a sonar lo
     *   convertía en "por dónde voy" por delante de uno con progreso real, solo por ser más
     *   reciente. Medido en Dragon Ball el 2026-08-12: el e126 con 3:30 vistos perdía contra el
     *   e127 y el e128, abiertos después y con la fila en 0. Y como "Continuar viendo" SÍ filtra
     *   por posición (`observeContinueWatching`), las dos superficies contestaban distinto: la fila
     *   del home ofrecía el e126 y el detalle decía "vas en el e128".
     *
     * Sigue sin haber piso de segundos, a propósito: un capítulo con dos segundos reproducidos es
     * "donde vas" si es lo último que reprodujiste de verdad. Lo que se descarta no es "poco
     * progreso" sino "ninguno".
     */
    val inProgressEpisode: Episode?
        get() = porDondeVas?.takeIf { !it.esSiguiente }?.let { elegido -> episodes.find { it.id == elegido.episodeId } }

    /**
     * La regla compartida con "Continuar viendo", resuelta contra la lista de capítulos que este
     * detalle ya tiene en memoria. Ver [com.arkiv.player.data.PorDondeVas]: acá SIN piso de
     * segundos, porque en el detalle "donde vas" es donde vas aunque hayas visto dos segundos.
     */
    private val porDondeVas: CapituloAOfrecer?
        get() = PorDondeVas.elegir(
            episodes.mapNotNull { ep ->
                progress[ep.id]?.let {
                    ProgresoDeCapitulo(ep.id, it.positionMs, it.watched, it.lastPlayedAt)
                }
            },
            siguienteDe = { id ->
                val i = episodes.indexOfFirst { it.id == id }
                if (i >= 0) episodes.getOrNull(i + 1)?.id else null
            },
        )

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
     * el E11, saltándose E4-E9. La regla es por RECENCIA y la decide [PorDondeVas], la MISMA que
     * arma "Continuar viendo" en el home: se ancla en lo último que reprodujiste (terminado o no) y
     * ofrece ese capítulo si quedó a medias, o el que le sigue si lo terminaste. Consecuencia
     * asumida (no es un bug, no "arreglar" esto): si terminaste toda la serie y después revisitaste
     * el E1, "Reproducir" pasa a ofrecer el E2 -- es lo que espera alguien que está reviendo.
     *
     * Los dos respaldos de abajo son de ESTA superficie y no de la regla: el botón "Reproducir" no
     * puede quedarse sin capítulo. Si nunca se vio nada, cae al primero sin ver; si se vio todo (no
     * hay "siguiente" tras el último terminado), vuelve a empezar por el primero. El home, en
     * cambio, prefiere no mostrar la tarjeta antes que ofrecer algo que ya viste.
     */
    val resumeEpisode: Episode?
        get() = porDondeVas?.let { elegido -> episodes.find { it.id == elegido.episodeId } }
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
    /**
     * Bajador best-effort del JPEG remoto (fase 2 de sync entre dispositivos): lo dispara
     * [observeContinueWatching] y [observeEpisodeFrames], que son los dos lugares donde el
     * repositorio ya sabe que un frame hace falta pintarlo (ver el doc de cada uno). Nullable con
     * default null por el mismo motivo que [almacenDeFrames]: sin bajador, esos dos Flow siguen
     * funcionando igual que hoy —simplemente no disparan ninguna bajada— para no romper los call
     * sites que arman un repositorio suelto (pruebas, herramientas).
     */
    private val bajadorDeFrames: BajadorDeFrames? = null,
    /**
     * Avisa al gateway cuando un capítulo pasa a visto, para que reconsidere la fila "Para ti"
     * (spec `2026-08-16-recomendaciones-por-historial`). Nullable con default null por el mismo
     * motivo que [bajadorDeFrames]: sin avisador, los call sites de test/herramientas siguen
     * guardando progreso igual, solo que sin avisarle a nadie.
     */
    private val avisadorDeRecomendaciones: com.arkiv.player.data.gateway.AvisadorDeRecomendaciones? = null,
    /**
     * Dónde correr `bajadorDeFrames.bajarPendientes()` sin bloquear la emisión del Flow que la
     * dispara. Un scope propio (no el de la UI) a propósito: la bajada tiene que sobrevivir a que
     * la pantalla que la disparó se cierre a mitad de camino, igual que el push/pull de
     * `CloudSyncManager`. El default es un scope nuevo por si algún call site no inyecta uno; en la
     * app real `AppGraph` pasa el mismo `applicationScope` que usa para todo lo demás.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val itemDao = db.itemDao()
    private val playbackDao = db.playbackDao()
    private val downloadDao = db.downloadDao()
    private val skipMarkerDao = db.skipMarkerDao()
    private val artworkDao = db.artworkDao()
    private val episodeStillDao = db.episodeStillDao()
    private val episodeFrameDao = db.episodeFrameDao()
    private val liveFavoriteDao = db.liveFavoriteDao()
    private val liveRecentDao = db.liveRecentDao()

    /**
     * Lanza `bajadorDeFrames.bajarPendientes([episodeIds])` en [scope], sin esperar el resultado: a
     * quien llama (una `map` de Flow) le urge devolver la fila YA, con lo que haya en disco en ESTE
     * instante -el respaldo de TMDB si el archivo todavía no bajó.
     *
     * Se le pasan SOLO los capítulos que se están por pintar: la bajada es perezosa por diseño (los
     * bytes se traen cuando hay que pintar esa tarjeta), no "vaciar la cola de la cuenta". Sin ese
     * filtro, abrir el home en un aparato desincronizado disparaba ~97 descargas para pintar 6.
     *
     * CÓMO REAPARECE EL FRAME SOLO: `bajarPendientes` escribe el archivo y toca la fila en
     * `episode_frame`; Room invalida esa TABLA y con eso reemite los Flow que la consultan. Eso
     * incluye a los dos llamadores de acá, pero por caminos distintos: [observeEpisodeFrames]
     * consulta `episode_frame` de por sí, y [observeContinueWatching] NO —su query toca `playback`,
     * `episodes`, `items` y `episode_still`— así que se le combina a propósito
     * `episodeFrameDao.observeTodos()`, que es lo que la vuelve sensible a la tabla del frame. Sin
     * ese combine la tarjeta del home se quedaba con el still de TMDB aunque el JPEG ya estuviera
     * en disco.
     *
     * Sin costo si no hay bajador (call sites de test/herramientas, ver el doc del constructor), si
     * no hay nada que pintar, ni si ya hay una pasada en curso (`BajadorDeFrames` se protege solo,
     * ver su doc).
     */
    private fun dispararBajadaDeFrames(episodeIds: Collection<String>) {
        val bajador = bajadorDeFrames ?: return
        if (episodeIds.isEmpty()) return
        scope.launch { bajador.bajarPendientes(episodeIds) }
    }

    /**
     * Lanza [AvisadorDeRecomendaciones.avisar] en [scope], sin esperar el resultado.
     *
     * Se llama SIEMPRE después de que el progreso ya quedó guardado (`playbackDao.upsert`), nunca
     * antes: guardar que se vio un capítulo es lo importante, y no puede depender de que el
     * gateway responda rápido, de que haya red, o de nada de lo que pase acá adentro -- por eso
     * `scope.launch` (no se espera) y el avisador traga sus propios errores (ver su doc).
     */
    private fun dispararRefrescoDeRecomendaciones() {
        val avisador = avisadorDeRecomendaciones ?: return
        scope.launch { avisador.avisar() }
    }

    fun observeLibrary(): Flow<List<LibraryRow>> = itemDao.observeLibrary()

    /**
     * `itemId -> cuándo se reprodujo por última vez algo de ese ítem`, para el orden de la
     * biblioteca. Un ítem que nunca se reprodujo no está en el mapa.
     */
    fun observeUltimaReproduccion(): Flow<Map<String, Long>> =
        playbackDao.observeUltimaReproduccion().map { filas ->
            filas.associate { it.itemId to it.ultimaMs }
        }

    /**
     * La biblioteca ordenada por lo último que viste (ver
     * [com.arkiv.player.data.biblioteca.OrdenDeBiblioteca]). La consume la grilla del teléfono.
     *
     * Es un flow aparte y NO el orden de [observeLibrary] a propósito: esa consulta cruda la usan
     * `ensureArtwork`, la pantalla de descargas y el héroe del home del TV, a los que el reorden no
     * les aporta nada. Si el orden viviera en el SQL, la consulta pasaría a depender de `playback`
     * y Room re-emitiría la biblioteca entera cada vez que se guarda progreso — cada pocos segundos
     * mientras reproducís —, disparando una pasada de arte por fila en cada emisión.
     */
    fun observeLibraryOrdenada(): Flow<List<LibraryRow>> =
        combine(observeLibrary(), observeUltimaReproduccion()) { rows, ultimas ->
            com.arkiv.player.data.biblioteca.OrdenDeBiblioteca.filas(rows, ultimas)
        }

    /**
     * La biblioteca agrupada SIN ordenar por lo último visto: solo el agrupamiento de
     * [LibraryGrouping], en el orden de entrada de `observeLibrary()` (`addedAt DESC`).
     *
     * Privado a propósito: lo único que lo consume es [observeGroupMembers], al que el orden de
     * la lista de grupos no le sirve (resuelve los miembros de UNA llave). Si colgara del orden
     * por lo último visto, dependería de `observeUltimaReproduccion()` y recalcularía el
     * agrupamiento cada vez que se guarda progreso en CUALQUIER ítem de la biblioteca, aunque el
     * resultado fuera idéntico.
     */
    private fun observeLibraryGroupsSinOrden(): Flow<List<LibraryGroup>> =
        LibraryGrouping.groupsFlow(observeLibrary(), observeArtwork())

    /**
     * La biblioteca ya agrupada: una entrada por serie, no por adquisición. Ver [LibraryGrouping].
     * `observeLibrary()` sigue existiendo para quien necesite las filas crudas (la pantalla de
     * biblioteca del teléfono, el sync).
     *
     * El orden final es por lo último visto ([com.arkiv.player.data.biblioteca.OrdenDeBiblioteca]),
     * no por fecha de agregado: el `sortedByDescending` de [LibraryGrouping.group] queda como
     * desempate, porque el orden de Kotlin es estable.
     */
    fun observeLibraryGroups(): Flow<List<LibraryGroup>> =
        combine(
            observeLibraryGroupsSinOrden(),
            observeUltimaReproduccion(),
        ) { grupos, ultimas ->
            com.arkiv.player.data.biblioteca.OrdenDeBiblioteca.grupos(grupos, ultimas)
        }

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
     * ya sale de `observeLibrary()` vía [observeLibraryGroupsSinOrden]) en vez de volver a
     * combinar `observeLibrary()` acá aparte.
     *
     * Cuelga de [observeLibraryGroupsSinOrden], NO de [observeLibraryGroups], a propósito: esto
     * resuelve los miembros de UNA sola llave de grupo, así que el orden de la lista completa de
     * grupos no lo afecta para nada. Colgarlo del orden por lo último visto haría que el detalle
     * recalculara el agrupamiento cada vez que se guarda progreso en cualquier ítem de la
     * biblioteca, aunque el resultado para esta llave no cambiara. No "unificar" esto con
     * [observeLibraryGroups] sin volver a leer este comentario.
     */
    fun observeGroupMembers(groupKey: String): Flow<List<LibraryRow>> =
        observeLibraryGroupsSinOrden().map { groups ->
            LibraryGrouping.resolveMembers(groupKey, groups, groups.flatMap { it.members })
        }

    /**
     * "Continuar viendo", con el frame capturado de cada capítulo si ya está en disco.
     *
     * El `combine` con `episodeFrameDao.observeTodos()` NO aporta datos —se descarta el segundo
     * valor— sino INVALIDACIÓN: la consulta de `playback` no toca `episode_frame`, así que sin esto
     * Room no reemitía nada cuando el bajador publicaba un JPEG y la tarjeta se quedaba con el still
     * de TMDB. También es lo que hace que la bajada se dispare cuando la fila del frame llega por
     * sync DESPUÉS del progreso, que es el orden real del push (`progress` antes que
     * `episode_frames`). Ver [dispararBajadaDeFrames].
     */
    fun observeContinueWatching(): Flow<List<ContinueRow>> =
        combine(
            playbackDao.observeProgresoConSiguiente(),
            episodeFrameDao.observeTodos(),
        ) { rows, _ -> rows }.map { rows ->
            // Qué capítulo va por cada serie lo decide PorDondeVas, que es la parte pura y testeada
            // (una tarjeta por ítem, ordenadas por lo último que reprodujiste). Acá solo se traduce
            // la fila de la base a lo que esa regla entiende.
            PorDondeVas.porItem(
                rows.map {
                    ProgresoEnItem(
                        itemId = it.itemId,
                        progreso = ProgresoDeCapitulo(
                            episodeId = it.episodeId,
                            positionMs = it.positionMs,
                            watched = it.watched,
                            lastPlayedAt = it.lastPlayedAt,
                        ),
                        siguienteEpisodeId = it.siguienteEpisodeId,
                    )
                },
                minPositionMs = CONTINUE_WATCHING_MIN_MS,
            )
        }.map { elecciones ->
            if (elecciones.isEmpty()) return@map emptyList()
            // El IN no conserva el orden y la elección puede apuntar a un capítulo sin fila de
            // playback, así que se reordena acá y se pisa lastPlayedAt con el del ancla: el
            // capítulo ofrecido puede no haberse reproducido nunca, pero la serie sí, y es la
            // serie la que tiene que estar arriba en la fila.
            val porId = playbackDao.filasParaContinuar(elecciones.map { it.episodeId })
                .associateBy { it.episodeId }
            elecciones.mapNotNull { eleccion ->
                porId[eleccion.episodeId]?.copy(lastPlayedAt = eleccion.lastPlayedAt)
            }
        }.map { filas ->
            // El framePath NO sale de la query (ver el doc del campo en ContinueRow): se resuelve
            // acá, del disco, después del dedup/take(20) de arriba para no gastar File.exists()
            // de más en filas que ni se van a mostrar. Son ~6 filas por emisión: despreciable.
            //
            // Justo acá es donde se sabe que a un capítulo le falta el frame en disco (framePath
            // sale null): es el punto natural para disparar la bajada pendiente, y con la lista
            // EXACTA de lo que se va a pintar. Ver el doc de dispararBajadaDeFrames() para el
            // mecanismo completo (por qué no bloquea, y cómo la tarjeta termina pintando el frame
            // real sin que nadie la recargue a mano).
            dispararBajadaDeFrames(filas.map { it.episodeId })
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
     * No hace nada si no hay [tmdbApi] (el parámetro es nullable, con default `null`).
     */
    suspend fun ensureArtwork(rows: List<LibraryRow>) {
        val tmdb = tmdbApi ?: return
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
                runCatching { tmdb.images(type, match.item.id) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            artworkDao.upsert(
                ArtworkEntity(
                    itemId = row.identifier,
                    // El id se guarda igual aunque el match sea por descarte: sirve para el arte.
                    tmdbId = match?.item?.id,
                    // El TIPO solo si el match fue exacto, porque es lo que [LibraryGrouping] exige
                    // para juntar dos filas en una tarjeta. Un id "más o menos" da un backdrop
                    // aceptable; una identidad "más o menos" funde obras distintas.
                    tmdbType = match?.takeIf { it.exacto }?.let { type },
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
    private suspend fun searchTmdbMatch(tmdb: TmdbApi, type: String, title: String): TmdbMatch? {
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
        val tmdb = tmdbApi ?: return false
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
            val tipoSiExacto = type.takeIf { match.exacto }
            if (match.item.id == stored && existing.tmdbType == tipoSiExacto) continue
            val backdrops = runCatching { tmdb.images(type, match.item.id) }.getOrNull()
            if (backdrops == null) {
                complete = false
                continue
            }
            artworkDao.upsert(
                ArtworkEntity(
                    itemId = row.identifier,
                    tmdbId = match.item.id,
                    // Misma regla que en ensureArtwork: la reparación no puede ASCENDER un match
                    // por descarte a identidad, que es justo lo que arregla este cambio.
                    tmdbType = tipoSiExacto,
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
     * Mapa episodeId -> ruta en disco del frame capturado, para que el detalle de una serie pinte
     * la escena real en vez del still de TMDB. Mismo mecanismo que [observeContinueWatching]
     * (`ContinueRow.framePath`), pero acá el disparador es una consulta de Room en vez de un
     * `List<ContinueRow>` ya en memoria.
     *
     * SUTILEZA a propósito: la fila de `episode_frame` se usa solo como DISPARADOR (Room notifica
     * el Flow cuando cambia una fila; el disco no notifica nada), y la ruta en sí sale SIEMPRE de
     * `almacenDeFrames.rutaSiExiste`, igual que en el home — es la única fuente de verdad de dónde
     * está el JPEG. Consecuencia asumida: si alguna vez se guardó el JPEG pero falló la escritura
     * de la fila (o viceversa), el detalle no lo mostraría aunque el home sí. Es un caso raro
     * (la escritura de fila y archivo son parte de la misma captura) y se corrige solo con la
     * próxima captura del capítulo.
     */
    fun observeEpisodeFrames(itemId: String): Flow<Map<String, String>> =
        episodeFrameDao.observeForItem(itemId).map { rows ->
            // Mismo punto de disparo que observeContinueWatching y mismo motivo: acá es donde se
            // resuelve, fila por fila, si el frame de cada capítulo ya está en disco -así que acá
            // es donde se nota cuál todavía no bajó. Se piden solo los capítulos de ESTA serie, que
            // son los que la pantalla va a pintar.
            dispararBajadaDeFrames(rows.map { it.episodeId })
            rows.mapNotNull { r -> almacenDeFrames?.rutaSiExiste(r.episodeId)?.let { r.episodeId to it } }.toMap()
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
        val tmdb = tmdbApi ?: return
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
        } else if (episodes.any { NumeracionCodificada.coordenadas(it.itemId, it.section, it.orderIndex) != null }) {
            // Torrent y web: el orderIndex trae la numeración codificada. Quién la trae y quién no
            // lo decide la fuente de la fila, no que el número pase de 1000 — mirar el número dejaba
            // afuera la temporada 0 (los especiales, que dan menos de 1000) y mandaba esas series a
            // la rama de repartir por conteo, que les ponía el still de otro capítulo.
            episodes.mapNotNull { ep ->
                NumeracionCodificada.coordenadas(ep.itemId, ep.section, ep.orderIndex)?.let { ep.id to it }
            }.toMap()
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
            // La numeración va también en SU columna, no solo codificada en el orderIndex. Es el
            // dato exacto y ya se conoce acá (llega por parámetro); guardarlo evita tener que
            // reconstruirlo después, que es donde la temporada 0 se confundía con un correlativo.
            season = season,
            episode = episode,
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
            derivativeSize = 0, season = season, episode = episode,
            torrentFileIndex = null, torrentData = magnet,
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
            derivativeSize = 0, season = season, episode = episode,
            torrentFileIndex = null, torrentData = pageUrl,
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
     * El ref con el que pedirle al gateway la identidad de un ítem de Magis guardado sin ella, o
     * null si no hay nada que reparar. La regla vive en [MagisEntities.refParaReparar]; acá solo se
     * lee la fila.
     */
    suspend fun refDeMagisParaReparar(itemId: String): String? {
        val fila = itemDao.getItem(itemId) ?: return null
        return MagisEntities.refParaReparar(fila.identifier, fila.tmdbId, fila.torrentData)
    }

    /**
     * Le pega a un ítem de Magis la identidad que el gateway ahora sí resuelve: el `tmdbId` de la
     * serie y lo que TMDB sepa de cada capítulo.
     *
     * Los stills van por [guardarStillsDeMagis], el mismo (y único) punto de escritura que usan
     * `addMagisSeason`/`addMagisSource`, así que se respeta la mezcla que no pisa lo ya guardado.
     * No toca los episodios ni el resto del ítem: esto repara metadata, no reescribe la biblioteca.
     */
    suspend fun aplicarIdentidadDeMagis(
        itemId: String,
        tmdbId: Int?,
        capitulos: List<CapituloDeTemporada>,
    ) {
        val fila = itemDao.getItem(itemId) ?: return
        if (tmdbId != null && tmdbId > 0 && fila.tmdbId != tmdbId) {
            itemDao.upsertItem(fila.copy(tmdbId = tmdbId, updatedAt = clock()))
        }
        if (capitulos.isNotEmpty()) {
            guardarStillsDeMagis(itemId, MagisEntities.stillsDeTemporada(itemId, capitulos, clock()))
        }
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
        // El JPEG se borra de verdad (nadie más lo reclama), pero la fila del frame queda como
        // tombstone y SÍ viaja por el sync (ver `DestructorDeFrames.destruir`): si no se
        // propagara, sacar la serie de la biblioteca en un dispositivo dejaría el frame "resucitar"
        // en los demás la próxima vez que sincronizaran.
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
        liveFavorites = liveFavoriteDao.getAll(),
        liveRecents = liveRecentDao.getAll(),
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
                if (pb.watched) {
                    borrarFrameDe(pb.episodeId)
                    // El TV y el celular sincronizando por LAN avisando lo mismo no es un
                    // problema: el gateway dedupea por cuenta con su ventana de 24 h (ver el
                    // spec), así que dos avisos del mismo capítulo visto producen un solo cálculo.
                    dispararRefrescoDeRecomendaciones()
                }
            }
        }
        for (m in snapshot.markers) {
            val local = skipMarkerDao.get(m.itemId)
            if (local == null || m.updatedAt > local.updatedAt) {
                skipMarkerDao.upsert(m)
                changes++
            }
        }

        // Favoritos y recientes de TV en vivo (Task 10): mismo `aAplicar` que items/episodes.
        // `live_channels_cache` NO entra acá -- no viaja por el sync, ver [LiveChannelCacheEntity].
        val liveFavorites = com.arkiv.player.sync.SyncMerge.aAplicar(
            locales = liveFavoriteDao.getAll(), remotas = snapshot.liveFavorites,
            llave = { it.code }, updatedAt = { it.updatedAt },
        )
        liveFavorites.forEach { liveFavoriteDao.guardar(it) }
        changes += liveFavorites.size

        val liveRecents = com.arkiv.player.sync.SyncMerge.aAplicar(
            locales = liveRecentDao.getAll(), remotas = snapshot.liveRecents,
            llave = { it.code }, updatedAt = { it.updatedAt },
        )
        liveRecents.forEach { liveRecentDao.anotar(it) }
        changes += liveRecents.size

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
     * alimenta tres consumidores que no distinguen "recién visto" de "reabrí algo viejo":
     * [PlaybackDao.observeVistos] (vía `VistosDeLaBiblioteca.cruzar`, ordena "Ya visto" de la
     * biblioteca), [PlaybackDao.seriesConProgreso] (vía `SeriesPorRevisar.elegir`, decide qué
     * series barrer contra la red buscando capítulo nuevo) y [PlaybackDao.observeUltimaReproduccion]
     * (vía `OrdenDeBiblioteca`, decide qué tarjeta sube al tope de "Mi biblioteca"). Sin este corte,
     * reabrir tres segundos un capítulo viejo subía esa serie al tope de "Ya visto" y la metía otra
     * vez en el barrido de red por hasta 30 días, sin que se haya visto nada nuevo.
     *
     * Este corte solo protege el instante inicial de `load()`: en cuanto el player conoce la
     * duración, `savePlayback` pisa `lastPlayedAt` sin excepción (no hay piso de segundos, ver el
     * spec de orden de biblioteca), aunque la posición alcanzada no llegue al 60% y `watched` quede
     * en `false`. Así que sí, reabrir un capítulo terminado y cerrarlo a los pocos segundos igual
     * sube ese ítem al tope de "Mi biblioteca" apenas se conoce la duración — es intencional, no un
     * bug de este corte.
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

    /** Persiste posición de reproducción. Marca visto según [UmbralDeVisto]. */
    suspend fun savePlayback(episodeId: String, positionMs: Long, durationMs: Long) {
        val watched = UmbralDeVisto.yaLoViste(positionMs, durationMs)
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
        if (watched) {
            borrarFrameDe(episodeId)
            // El progreso YA quedó guardado arriba (`playbackDao.upsert`): este aviso es un extra
            // que corre después y en su propio scope, nunca puede ser la causa de que un capítulo
            // visto no se guarde.
            dispararRefrescoDeRecomendaciones()
        }
    }

    suspend fun setWatched(episodeId: String, watched: Boolean) {
        val existing = playbackDao.get(episodeId)
        playbackDao.upsert(
            PlaybackEntity(
                episodeId = episodeId,
                positionMs = if (watched) (existing?.durationMs ?: 0L) else 0L,
                durationMs = existing?.durationMs ?: 0L,
                watched = watched,
                // Marcar como visto SÍ es una interacción con el capítulo: pisa lastPlayedAt.
                // Desmarcar NO lo es (es corregir un error, no "reproducir"), así que se preserva
                // lo que ya había; si no, la tarjeta saltaría al tope de la biblioteca sin que se
                // haya visto nada. Ver observeUltimaReproduccion, que ya no filtra por watched.
                lastPlayedAt = if (watched) clock() else (existing?.lastPlayedAt ?: clock()),
            )
        )
        // Al desmarcar (watched = false) NO se borra nada: el capítulo vuelve a estar en curso y
        // el frame que haya sigue siendo válido.
        if (watched) {
            borrarFrameDe(episodeId)
            // Mismo motivo que en savePlayback: el progreso ya está guardado, esto es un extra.
            dispararRefrescoDeRecomendaciones()
        }
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
internal data class TmdbMatch(
    val item: TmdbItem,
    /**
     * Si el título coincidió DE VERDAD, o si es el primer resultado por descarte.
     *
     * La distinción existe porque los dos usos del match tienen tolerancias distintas: para sacarle
     * un backdrop a "Dragon Ball Kai", el "Dragon Ball Z Kai" de TMDB sirve de sobra; para decir que
     * dos filas son LA MISMA OBRA —que es lo que hace [LibraryGrouping] agrupando por
     * `tv:<tmdbId>`— no alcanza ni de lejos. Sin esta marca, un título que TMDB no conoce
     * ("Construido por los hombres", que es un capítulo de Evangelion) se llevaba el id del primer
     * resultado que cayera y fundía dos obras sin relación en una sola tarjeta.
     */
    val exacto: Boolean,
)

internal fun pickTmdbMatch(query: String, results: List<TmdbItem>): TmdbMatch? {
    val q = WebTmdbMatcher.normalize(query)
    if (q.isBlank()) return results.firstOrNull()?.let { TmdbMatch(it, exacto = false) }
    results.firstOrNull {
        WebTmdbMatcher.normalize(it.title) == q || WebTmdbMatcher.normalize(it.originalTitle) == q
    }?.let { return TmdbMatch(it, exacto = true) }
    return results.firstOrNull()?.let { TmdbMatch(it, exacto = false) }
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
