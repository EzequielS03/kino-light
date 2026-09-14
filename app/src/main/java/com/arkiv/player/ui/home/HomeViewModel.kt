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
     * The library ordered by what you last watched, for the "Mi biblioteca" grid.
     *
     * A separate subscription from [library] on purpose: raw [library] feeds the `init`'s
     * `onEach { ensureArtwork(rows) }` (one query per row on every emission) and the TV home's
     * hero, and shouldn't re-emit every time progress is saved.
     */
    val orderedLibrary: StateFlow<List<LibraryRow>> = repo.observeLibraryOrdered()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val continueWatching: StateFlow<List<ContinueRow>> = repo.observeContinueWatching()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** itemId -> TMDB art (backdrops) to paint on the home. */
    val artwork: StateFlow<Map<String, ArtworkEntity>> = repo.observeArtwork()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _rows = MutableStateFlow(buildRowSpecs(emptyList(), emptyList(), emptyList()))
    val rows: StateFlow<List<HomeRowSpec>> = _rows.asStateFlow()

    /** rowId -> titles already loaded (in-memory cache; survives as long as the ViewModel lives). */
    private val _rowItems = MutableStateFlow<Map<String, List<TitleCard>>>(emptyMap())
    val rowItems: StateFlow<Map<String, List<TitleCard>>> = _rowItems.asStateFlow()

    /** rowId of the rows that already finished loading (with or without results; to be able to hide the empty ones). */
    private val _rowsLoaded = MutableStateFlow<Set<String>>(emptySet())
    val rowsLoaded: StateFlow<Set<String>> = _rowsLoaded.asStateFlow()

    private val guard = LoadGuard()

    /** Titles already shown in some row, to not repeat them in the next ones. */
    private val seenCards = mutableSetOf<String>()

    init {
        // Every time the library changes, resolves the art of the items that don't have it yet.
        // ensureArtwork ignores the ones already resolved, so re-emissions are cheap.
        library
            .onEach { rows -> repo.ensureArtwork(rows) }
            .launchIn(viewModelScope)

        // One-time pass to repair art that ended up pointing at the wrong title before
        // pickTmdbMatch existed (the Dragon Balls with Dragon Ball Z's tmdbId). ensureArtwork
        // can't do it: it skips everything that already has a tmdbId. Only marked done if it
        // finished completely, so a start with no network retries it on the next one.
        if (!settings.artworkRematchDone.value) {
            viewModelScope.launch {
                val rows = library.first { it.isNotEmpty() }
                if (repo.repairArtworkMatches(rows)) settings.setArtworkRematchDone(true)
            }
        }

        // Genres are requested only once to build the rows; if it fails, the fixed ones are left.
        viewModelScope.launch {
            val movie = runCatching { tmdbApi.genres("movie") }.getOrDefault(emptyList())
            val tv = runCatching { tmdbApi.genres("tv") }.getOrDefault(emptyList())
            val anime = runCatching { aniListApi.genres() }.getOrDefault(emptyList())
            if (movie.isNotEmpty() || tv.isNotEmpty() || anime.isNotEmpty()) _rows.value = buildRowSpecs(movie, tv, anime)
        }
    }

    /**
     * Loads a row's titles the first time it's requested (idempotent).
     *
     * Mind the order: genre rows only exist in [_rows] after `genres()` resolves in the init. If
     * the guard were consumed before the spec is found, a genre row requested early (spec still
     * absent) would be marked "already started" forever and would never load once the spec
     * showed up. That's why the spec is looked up first and the guard is only consumed if it exists.
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
            // Dedup across rows: a title stays in the first row it appears in. Without this,
            // cartelera/populares/tendencias and the genres show almost the same movies. Done here
            // (not in the UI) so the result is stable and doesn't change on recomposition.
            val fresh = dedupAgainst(seenCards, cards)
            seenCards += fresh.map { cardKey(it) }
            _rowItems.value = _rowItems.value + (id to fresh)
            _rowsLoaded.value = _rowsLoaded.value + id
        }
    }
}
