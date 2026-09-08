package com.arkiv.player.ui.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.data.catalog.AniListApi
import com.arkiv.player.data.catalog.AnimeShow
import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.catalog.TmdbDetail
import com.arkiv.player.data.gateway.toPlaySource
import com.arkiv.player.ui.catalog.PlaySource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Ruta de navegación a la pantalla de detalle según la card y el S/E opcional del REFINE.
 *  Ya NO se usa desde el wizard (Continuar ahora se queda en fase RESULTS), pero se conserva
 *  por si hace falta un handoff externo más adelante. */
fun handoffRouteFor(card: TitleCard, season: Int?, episode: Int?): String = when (card.kind) {
    "anime" -> buildString {
        append("catalog_anime/").append(card.anilistId)
        if (episode != null) append("?episode=").append(episode)
    }
    "movie" -> "cine/movie/${card.tmdbId}"
    else -> buildString { // "series"
        append("cine/tv/").append(card.tmdbId)
        if (season != null && episode != null) append("?season=").append(season).append("&episode=").append(episode)
    }
}

/** Presupuesto del fan-out del gateway para magis. */
private const val GATEWAY_BUDGET_MS = 15000

/** El anime necesita más tiempo para expandir títulos (AniList/Fribb/Simkl) del lado del
 *  servidor antes de poder buscar. */
private const val GATEWAY_BUDGET_ANIME_MS = 50000

/** Cuántos resultados del gateway se publican de una. Uno por uno hace que Compose recomponga
 *  la lista entera por cada resultado, y con varias decenas la app llega a ANR. */
private const val GATEWAY_LOTE = 25

private const val GW = "ArkivGateway"

/**
 * ViewModel del wizard de búsqueda unificada: Fase QUERY (TMDB + AniList + directos archive),
 * paso REFINE (S/E opcional) y fase RESULTS (búsqueda multi-fuente magis/archive con S/E
 * inyectado si se dio, o solo por nombre).
 */
class SearchViewModel(
    private val tmdbApi: TmdbApi,
    private val aniListApi: AniListApi,
    private val settings: SettingsStore,
    private val arkivApiClient: com.arkiv.player.data.gateway.ArkivApiClient,
    private val searchHistory: com.arkiv.player.data.SearchHistoryRepo,
) : ViewModel() {

    private val _phase = MutableStateFlow(SearchPhase.QUERY)
    val phase: StateFlow<SearchPhase> = _phase.asStateFlow()

    private val _titleResults = MutableStateFlow<List<TitleCard>>(emptyList())
    val titleResults: StateFlow<List<TitleCard>> = _titleResults.asStateFlow()

    private val _directResults = MutableStateFlow<List<PlaySource>>(emptyList())
    val directResults: StateFlow<List<PlaySource>> = _directResults.asStateFlow()

    private val _loadingTitles = MutableStateFlow(false)
    val loadingTitles: StateFlow<Boolean> = _loadingTitles.asStateFlow()

    private val _loadingDirect = MutableStateFlow(false)
    val loadingDirect: StateFlow<Boolean> = _loadingDirect.asStateFlow()

    private val _selected = MutableStateFlow<TitleCard?>(null)
    val selected: StateFlow<TitleCard?> = _selected.asStateFlow()

    /**
     * Si lo que se está buscando salió de escribir texto y no de elegir una ficha del catálogo.
     *
     * Importa al GUARDAR: con una ficha real, el título y el póster de TMDB son la mejor metadata
     * que hay; con texto libre, la consulta no es metadata de nada —"dragon ball 137" no es el
     * nombre de nada— y lo que corresponde es el nombre propio de cada fuente.
     */
    private val _busquedaPorTexto = MutableStateFlow(false)
    val busquedaPorTexto: StateFlow<Boolean> = _busquedaPorTexto.asStateFlow()

    // --- Fase RESULTS: resultados multi-fuente (magis/archive) de la card elegida ---
    private val _sources = MutableStateFlow<List<PlaySource>>(emptyList())
    val sources: StateFlow<List<PlaySource>> = _sources.asStateFlow()

    private val _loadingMagis = MutableStateFlow(false)
    val loadingMagis: StateFlow<Boolean> = _loadingMagis.asStateFlow()

    private val _loadingArchive = MutableStateFlow(false)
    val loadingArchive: StateFlow<Boolean> = _loadingArchive.asStateFlow()

    private val _refineSeason = MutableStateFlow<Int?>(null)
    val refineSeason: StateFlow<Int?> = _refineSeason.asStateFlow()

    private val _refineEpisode = MutableStateFlow<Int?>(null)
    val refineEpisode: StateFlow<Int?> = _refineEpisode.asStateFlow()

    private val _detail = MutableStateFlow<TmdbDetail?>(null)
    val detail: StateFlow<TmdbDetail?> = _detail.asStateFlow()

    private val _animeShow = MutableStateFlow<AnimeShow?>(null)
    val animeShow: StateFlow<AnimeShow?> = _animeShow.asStateFlow()

    private var searchJob: Job? = null
    private var fraseJob: Job? = null

    /** Resultado de la búsqueda por descripción, o null si no se ha pedido en esta consulta. */
    private val _frase = MutableStateFlow<ResultadoDeFrase?>(null)
    val frase: StateFlow<ResultadoDeFrase?> = _frase.asStateFlow()

    private val _loadingFrase = MutableStateFlow(false)
    val loadingFrase: StateFlow<Boolean> = _loadingFrase.asStateFlow()

    /**
     * Búsqueda por descripción ("una de miedo de los 80 en español"), contra el gateway.
     *
     * Las cards que vuelven son de TMDB con id y título reales, así que [pickTitle] las abre por
     * el flujo de siempre. Un fallo del gateway deja el resultado en vacío SIN interpretación:
     * la pantalla muestra "nada con esa descripción" y nadie ve un error — mismo criterio que la
     * trivia, porque esto también es accesorio.
     */
    fun buscarPorFrase(q: String) {
        if (q.isBlank()) return
        enHistorial { searchHistory.addQuery(q) }
        fraseJob?.cancel()
        fraseJob = viewModelScope.launch {
            _loadingFrase.value = true
            val r = runCatching { arkivApiClient.buscarPorFrase(q) }.getOrNull()
            _frase.value = ResultadoDeFrase(
                interpretado = r?.interpretado,
                cards = (r?.items ?: emptyList()).map { it.toTitleCard() },
            )
            _loadingFrase.value = false
        }
    }
    private var sourceJob: Job? = null

    // --- historial del buscador -------------------------------------------
    // Graba el ViewModel, no la pantalla: así da igual quién dispare la búsqueda y hay un solo
    // lugar donde mirar. El TV usa el mismo ViewModel y por eso también graba acá; su pantalla
    // muestra su propio historial (kind "tv"), que es otra lista.
    val recentQueries: StateFlow<List<String>> = searchHistory.queries
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val recentTitles: StateFlow<List<com.arkiv.player.data.RecentTitle>> = searchHistory.titles
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Best-effort: si Room falla, la búsqueda sigue. El historial nunca rompe el buscar.
    private fun enHistorial(bloque: suspend () -> Unit) {
        viewModelScope.launch { runCatching { bloque() } }
    }

    fun forgetQuery(q: String) = enHistorial { searchHistory.removeQuery(q) }
    fun forgetTitle(t: com.arkiv.player.data.RecentTitle) = enHistorial { searchHistory.removeTitle(t) }
    fun clearHistory() = enHistorial { searchHistory.clear() }

    /** Lanza la búsqueda unificada de Fase 1: TMDB + anime (títulos) y archive (directos). */
    fun search(q: String) {
        searchJob?.cancel()
        // Una consulta nueva (o limpiar) invalida la búsqueda por descripción anterior: dejarla
        // pintada debajo de otra consulta sería mezclar respuestas de dos preguntas distintas.
        fraseJob?.cancel()
        _frase.value = null
        _loadingFrase.value = false
        if (q.isBlank()) {
            _titleResults.value = emptyList()
            _directResults.value = emptyList()
            _loadingTitles.value = false
            _loadingDirect.value = false
            return
        }
        enHistorial { searchHistory.addQuery(q) }
        searchJob = viewModelScope.launch {
            _loadingTitles.value = true
            _loadingDirect.value = true

            var tmdbCards: List<TitleCard> = emptyList()
            var animeCards: List<TitleCard> = emptyList()
            var tmdbDone = false
            var animeDone = false

            // Las dos fuentes se pisan mucho (todo el anime que además está en TMDB), y en la
            // grilla eso son dos cards con el mismo nombre. Se limpia al publicar, que es el único
            // punto por el que pasan las dos tandas.
            fun publicarTitulos() { _titleResults.value = sinRepetidos(tmdbCards + animeCards) }

            val tmdbJob = launch {
                tmdbCards = runCatching { tmdbApi.searchMulti(q) }.getOrDefault(emptyList()).map { it.toTitleCard() }
                tmdbDone = true
                publicarTitulos()
                if (animeDone) _loadingTitles.value = false
            }
            val animeJob = launch {
                animeCards = runCatching { aniListApi.browse(1, "SEARCH_MATCH", q, null) }.getOrDefault(emptyList()).map { it.toTitleCard() }
                animeDone = true
                publicarTitulos()
                if (tmdbDone) _loadingTitles.value = false
            }

            // archive.org (búsqueda "directa") se borró en la poda de esta rama: no hay resultados
            // directos que ofrecer, así que la sección queda vacía de una.
            _directResults.value = emptyList()
            _loadingDirect.value = false

            tmdbJob.join()
            animeJob.join()
        }
    }

    /** Entrada desde el home: arranca ya en un título, saltándose la fase de escribir. */
    fun startFromShortcut(kind: String, tmdbId: Int?, anilistId: Long?) {
        if (selected.value != null) return   // ya arrancado (no repetir en recomposición)
        viewModelScope.launch {
            val card = when {
                kind == "anime" && anilistId != null ->
                    runCatching { aniListApi.details(anilistId) }.getOrNull()?.toTitleCard()
                tmdbId != null -> {
                    val type = if (kind == "movie") "movie" else "tv"
                    runCatching { tmdbApi.detail(type, tmdbId) }.getOrNull()?.let { d ->
                        TitleCard(
                            kind = if (kind == "movie") "movie" else "series",
                            tmdbId = d.id, anilistId = null, title = d.title,
                            posterUrl = d.posterUrl, year = d.year, overview = d.overview,
                        )
                    }
                }
                else -> null
            } ?: return@launch
            pickTitle(card)   // película -> RESULTS; serie/anime -> REFINE
        }
    }

    /** Elige una card: las películas van directo a RESULTS; series/anime pasan a REFINE. */
    fun pickTitle(card: TitleCard) {
        enHistorial { searchHistory.addTitle(card.toRecent()) }
        _selected.value = card
        _busquedaPorTexto.value = false
        if (card.kind == "movie") {
            runSourceSearch(null, null)
        } else {
            _phase.value = SearchPhase.REFINE
        }
    }

    /**
     * Busca fuentes por el texto crudo, sin pasar por el catálogo (botón "Ir" del TV): sirve
     * cuando uno se acuerda de un pedazo del nombre y no del título exacto con el que TMDB lo
     * tiene. La card la arma [cardDeTextoLibre]; de ahí en adelante es la misma búsqueda de
     * siempre, así que la lista de fuentes, los packs y la reproducción no cambian en nada.
     *
     * NO va al historial de títulos: una card sin póster ni ids ensuciaría la fila de "recientes"
     * del celu. La consulta sí la graba quien llama, en el historial de texto que le corresponda.
     */
    fun buscarFuentesPorTexto(q: String) {
        val card = cardDeTextoLibre(q) ?: return
        _selected.value = card
        _busquedaPorTexto.value = true
        // Metadata de la búsqueda anterior: `runSourceSearch` limpia `_detail` sola (la card no
        // tiene tmdbId), pero `_animeShow` solo se toca en la rama de anime — y si quedó la de un
        // anime buscado antes, la pantalla de fuentes mostraría SU título en vez del texto tecleado.
        _animeShow.value = null
        runSourceSearch(null, null)
    }

    /**
     * Búsqueda multi-fuente (magis/archive) de la card elegida: si viene season/episode se
     * inyectan en la búsqueda (capítulo concreto); si no, se busca solo por nombre. Progresiva:
     * cada fuente agrega resultados apenas los tiene.
     */
    fun runSourceSearch(season: Int?, episode: Int?) {
        val card = _selected.value ?: return
        sourceJob?.cancel()
        _phase.value = SearchPhase.RESULTS
        _sources.value = emptyList()
        _loadingArchive.value = true
        _loadingMagis.value = settings.useGateway.value
        _refineSeason.value = season
        _refineEpisode.value = episode

        sourceJob = viewModelScope.launch {
            var d: TmdbDetail? = null

            if (card.kind == "anime") {
                val show = card.anilistId?.let { id -> runCatching { aniListApi.details(id) }.getOrNull() }
                _animeShow.value = show
            } else {
                val tmdbType = if (card.kind == "movie") "movie" else "tv"
                d = card.tmdbId?.let { id -> runCatching { tmdbApi.detail(tmdbType, id) }.getOrNull() }
                _detail.value = d
            }

            fun append(new: List<PlaySource>) { _sources.value = _sources.value + new }

            // Magis, por el gateway. Detrás del flag para poder apagarlo sin publicar APK.
            if (settings.useGateway.value) {
                launch {
                    runCatching {
                        val ctx = com.arkiv.player.data.gateway.GatewaySearchQuery(
                            q = card.title,
                            type = when (card.kind) {
                                "movie" -> "movie"
                                "anime" -> "anime"
                                else -> "tv"
                            },
                            season = season ?: 0,
                            episode = episode ?: 0,
                            tmdbId = card.tmdbId ?: 0,
                            anilistId = card.anilistId ?: 0,
                            maxBytes = 0L,
                            budgetMs = if (card.kind == "anime") GATEWAY_BUDGET_ANIME_MS else GATEWAY_BUDGET_MS,
                            sources = "magis",
                        )
                        // Los resultados se acumulan y se publican EN LOTE. Publicar de a uno
                        // dispara una recomposición por resultado: con 20 de magis sobre 50+
                        // fuentes ya visibles, la UI se ahoga midiendo texto y la app da ANR.
                        val lote = mutableListOf<PlaySource>()
                        fun vaciarLote() {
                            if (lote.isEmpty()) return
                            append(lote.toList())
                            lote.clear()
                        }
                        arkivApiClient.search(ctx).collect { ev ->
                            when (ev) {
                                is com.arkiv.player.data.gateway.SearchEvent.ResultEvent -> {
                                    ev.item.toPlaySource()?.let { lote += it }
                                    if (lote.size >= GATEWAY_LOTE) vaciarLote()
                                }
                                is com.arkiv.player.data.gateway.SearchEvent.SourceError -> {
                                    Log.w(GW, "fuente ${ev.source} fallo: ${ev.error} (entrego ${ev.count})")
                                    vaciarLote()
                                }
                                is com.arkiv.player.data.gateway.SearchEvent.SourceDone -> {
                                    Log.w(GW, "fuente ${ev.source}: ${ev.count} en ${ev.ms}ms")
                                    vaciarLote()
                                }
                                else -> Unit
                            }
                        }
                        vaciarLote()
                    }.onFailure {
                        android.util.Log.w("ArkivGateway", "el gateway falló: ${it.message}")
                    }
                    _loadingMagis.value = false
                }
            }

            // archive.org se borró en la poda de esta rama: nada que agregar, la sección queda vacía.
            _loadingArchive.value = false
        }
    }

    /** Vuelve un paso: de RESULTS a REFINE (o QUERY si la card era película), de REFINE a QUERY. */
    fun back() {
        when (_phase.value) {
            SearchPhase.RESULTS -> {
                sourceJob?.cancel()
                _phase.value = if (_selected.value?.kind == "movie") SearchPhase.QUERY else SearchPhase.REFINE
            }
            SearchPhase.REFINE -> _phase.value = SearchPhase.QUERY
            SearchPhase.QUERY -> Unit
        }
        if (_phase.value == SearchPhase.QUERY) {
            _selected.value = null
            _busquedaPorTexto.value = false
        }
    }
}
