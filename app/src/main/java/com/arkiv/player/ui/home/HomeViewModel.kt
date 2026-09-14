package com.arkiv.player.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.data.catalog.AniListApi
import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.db.ArtworkEntity
import com.arkiv.player.data.db.ContinueRow
import com.arkiv.player.data.db.LibraryRow
import com.arkiv.player.ui.search.TitleCard
import com.arkiv.player.ui.search.toTitleCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    repo: ArkivRepository,
    private val tmdbApi: TmdbApi,
    private val aniListApi: AniListApi,
    private val settings: SettingsStore,
) : ViewModel() {

    val library: StateFlow<List<LibraryRow>> = repo.observeLibrary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * La biblioteca ordenada por lo último que viste, para la grilla de "Mi biblioteca".
     *
     * Es una suscripción aparte de [library] a propósito: [library] cruda alimenta el
     * `onEach { ensureArtwork(rows) }` del `init` (una consulta por fila en cada emisión) y el
     * héroe del home del TV, y no debe re-emitirse cada vez que se guarda progreso.
     */
    val bibliotecaOrdenada: StateFlow<List<LibraryRow>> = repo.observeLibraryOrdered()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val continueWatching: StateFlow<List<ContinueRow>> = repo.observeContinueWatching()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** itemId -> arte de TMDB (backdrops) para pintar en el home. */
    val artwork: StateFlow<Map<String, ArtworkEntity>> = repo.observeArtwork()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _rows = MutableStateFlow(buildRowSpecs(emptyList(), emptyList(), emptyList()))
    val rows: StateFlow<List<HomeRowSpec>> = _rows.asStateFlow()

    /** rowId -> títulos ya cargados (caché en memoria; sobrevive mientras viva el ViewModel). */
    private val _rowItems = MutableStateFlow<Map<String, List<TitleCard>>>(emptyMap())
    val rowItems: StateFlow<Map<String, List<TitleCard>>> = _rowItems.asStateFlow()

    /** rowId de las filas que ya terminaron de cargar (con o sin resultados; para poder ocultar las vacías). */
    private val _rowsLoaded = MutableStateFlow<Set<String>>(emptySet())
    val rowsLoaded: StateFlow<Set<String>> = _rowsLoaded.asStateFlow()

    private val guard = LoadGuard()

    /** Títulos ya mostrados en alguna fila, para no repetirlos en las siguientes. */
    private val seenCards = mutableSetOf<String>()

    init {
        // Cada vez que cambia la biblioteca, resuelve el arte de los ítems que aún no lo tengan.
        // ensureArtwork ignora los ya resueltos, así que las re-emisiones son baratas.
        library
            .onEach { rows -> repo.ensureArtwork(rows) }
            .launchIn(viewModelScope)

        // Pasada única para reparar el arte que quedó apuntando al título equivocado antes de que
        // existiera pickTmdbMatch (los Dragon Ball con el tmdbId de Dragon Ball Z). ensureArtwork
        // no puede hacerlo: salta todo lo que ya tenga tmdbId. Se marca hecha solo si terminó
        // entera, así un arranque sin red la reintenta en el siguiente.
        if (!settings.artworkRematchDone.value) {
            viewModelScope.launch {
                val rows = library.first { it.isNotEmpty() }
                if (repo.repairArtworkMatches(rows)) settings.setArtworkRematchDone(true)
            }
        }

        // Los géneros se piden una sola vez para construir las filas; si falla, quedan las fijas.
        viewModelScope.launch {
            val movie = runCatching { tmdbApi.genres("movie") }.getOrDefault(emptyList())
            val tv = runCatching { tmdbApi.genres("tv") }.getOrDefault(emptyList())
            val anime = runCatching { aniListApi.genres() }.getOrDefault(emptyList())
            if (movie.isNotEmpty() || tv.isNotEmpty() || anime.isNotEmpty()) _rows.value = buildRowSpecs(movie, tv, anime)
        }
    }

    /**
     * Carga los títulos de una fila la primera vez que se pide (idempotente).
     *
     * Ojo con el orden: las filas de género solo existen en [_rows] después de que
     * resuelve `genres()` en el init. Si el guard se consumiera antes de encontrar la
     * spec, una fila de género pedida temprano (spec aún ausente) quedaría marcada como
     * "ya iniciada" para siempre y jamás cargaría cuando la spec apareciera. Por eso
     * primero se busca la spec y solo si existe se consume el guard.
     */
    fun loadRow(id: String) {
        val spec = _rows.value.firstOrNull { it.id == id } ?: return
        if (!guard.shouldLoad(id)) return
        viewModelScope.launch {
            val cards: List<TitleCard> = when (val s = spec.source) {
                is RowSource.Curated ->
                    runCatching { tmdbApi.curated(s.type, s.category, 1) }.getOrDefault(emptyList()).map { it.toTitleCard() }
                is RowSource.Discover ->
                    runCatching { tmdbApi.discover(s.type, s.genreId, 1) }.getOrDefault(emptyList()).map { it.toTitleCard() }
                is RowSource.Anime ->
                    runCatching { aniListApi.browse(1, s.sort, null, s.genre) }.getOrDefault(emptyList()).map { it.toTitleCard() }
            }
            // Dedup entre filas: un título se queda en la primera fila donde aparece. Sin esto,
            // cartelera/populares/tendencias y los géneros muestran casi las mismas películas.
            // Se hace acá (no en la UI) para que el resultado sea estable y no cambie al recomponer.
            val fresh = dedupAgainst(seenCards, cards)
            seenCards += fresh.map { cardKey(it) }
            _rowItems.value = _rowItems.value + (id to fresh)
            _rowsLoaded.value = _rowsLoaded.value + id
        }
    }
}
