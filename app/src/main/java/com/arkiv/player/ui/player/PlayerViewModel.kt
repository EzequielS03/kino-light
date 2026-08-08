package com.arkiv.player.ui.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.ArchiveUrls
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.EpisodeTorrent
import com.arkiv.player.data.Quality
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.data.db.SkipMarkerEntity
import com.arkiv.player.data.model.Episode
import com.arkiv.player.data.model.VideoVariant
import com.arkiv.player.data.offline.ArkivOfflineApi
import com.arkiv.player.data.offline.PlaybackChoice
import com.arkiv.player.data.offline.PlaybackDecision
import com.arkiv.player.data.offline.PlaybackPreferenceStore
import com.arkiv.player.playback.ArchiveCacheProxy
import com.arkiv.player.playback.PlayerSource
import com.arkiv.player.playback.SourceKind
import com.arkiv.player.torrent.EpisodeHint
import com.arkiv.player.torrent.TorrentEngine
import com.arkiv.player.torrent.TorrentProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Datos de un episodio para el reproductor. */
data class PlayerData(
    val episodeId: String,
    val itemId: String,
    val title: String,
    val subtitle: String,
    val mediaUrl: String,       // reproducción local (mkv original o archivo descargado)
    val castUrl: String?,       // mp4 h.264 para Chromecast (compatible), o null
    val artworkUrl: String,     // carátula para la notificación
    val openingStartMs: Long?,
    val openingEndMs: Long?,
    val endingStartMs: Long?,
    val kind: SourceKind,       // fuente (archive/torrent/web) — la UI la usa p/ el overlay de descarga
    val referer: String? = null,    // headers para el stream web (algunos hosts exigen Referer)
    val userAgent: String? = null,
    val proxyUrl: String? = null,   // web: URL proxeada de respaldo si la directa falla (403/geo/anti-leech)
)

/** La sección como playlist: todos los episodios + dónde/cómo arrancar. */
data class PlaylistData(
    val items: List<PlayerData>,
    val startIndex: Int,
    val startPositionMs: Long,
)

/** Extras de una fuente web resuelta (subtítulos + headers sniffeados) para adjuntar en la UI. */
data class WebExtras(
    val episodeId: String,
    val headers: Map<String, String>,
    val subtitles: List<com.arkiv.player.data.catalog.web.ResolvedSub>,
)

/**
 * Pendiente de confirmación del usuario (Task 11): esta serie tiene un capítulo bajado a la NUC y
 * todavía no se le preguntó su preferencia (NUC vs en vivo). [PlayerScreen] observa este estado
 * para mostrar el diálogo; la respuesta se resuelve con [PlayerViewModel.resolveAskPlaybackSource].
 */
data class AskPlaybackSourceState(val episodeId: String, val seriesId: String, val nucItemId: Long)

class PlayerViewModel(
    private val repo: ArkivRepository,
    private val settings: SettingsStore,
    private val torrentEngine: TorrentEngine,
    private val archiveCacheProxy: ArchiveCacheProxy,
    private val webResolverApi: com.arkiv.player.data.catalog.web.WebResolverApi,
    private val arkivOfflineApi: ArkivOfflineApi,
    private val playbackPreferenceStore: PlaybackPreferenceStore,
    private val localLibrary: com.arkiv.player.data.local.LocalLibrary,
    private val localFileServer: com.arkiv.player.playback.LocalFileServer,
) : ViewModel() {

    private val _playlist = MutableStateFlow<PlaylistData?>(null)
    val playlist: StateFlow<PlaylistData?> = _playlist.asStateFlow()

    /** Error de resolución (torrent sin peers, .torrent ilegible, etc.) para que la pantalla lo muestre. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Progreso durante la fase de PRE-BUFFER (torrent): antes de emitir la playlist "lista" esperamos
     * a que la cabeza del archivo esté descargada, publicando peers/velocidad/% para que el Paso B
     * muestre un overlay "Cargando inicio…" en vez de una espera a ciegas. null = no estamos pre-buffeando.
     */
    private val _prepProgress = MutableStateFlow<TorrentProgress?>(null)
    val prepProgress: StateFlow<TorrentProgress?> = _prepProgress.asStateFlow()

    // Feedback mientras el resolver de blog snifea el stream de una fuente web (puede tardar).
    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    // Subtítulos + headers sniffeados de la fuente web, para que PlayerScreen los adjunte.
    private val _webExtras = MutableStateFlow<WebExtras?>(null)
    val webExtras: StateFlow<WebExtras?> = _webExtras.asStateFlow()

    // Task 11: hay un capítulo bajado a la NUC para esta serie y todavía no se preguntó la
    // preferencia (NUC vs en vivo) -> PlayerScreen muestra el diálogo de confirmación.
    private val _askPlaybackSource = MutableStateFlow<AskPlaybackSourceState?>(null)
    val askPlaybackSource: StateFlow<AskPlaybackSourceState?> = _askPlaybackSource.asStateFlow()

    /** Job cancelable de la precarga del siguiente capítulo (torrent pack / web / archive). */
    private var prefetchJob: kotlinx.coroutines.Job? = null

    /** Carga el episodio como playlist, ramificando por fuente (archive vs torrent vs web). */
    fun load(episodeId: String) {
        viewModelScope.launch {
            _error.value = null
            // Si está guardado en el dispositivo, gana sobre cualquier streaming. Va ANTES de
            // ramificar por fuente: da igual de dónde vino el archivo, ya está acá.
            //
            // ARCHIVE queda fuera a propósito: loadArchive() ya arma la playlist de la sección
            // pasando el archivo local por episodio, así que ya mezcla local y remoto bien. Meterlo
            // acá lo degradaría a un solo ítem y rompería el autoplay del siguiente capítulo.
            val kind = PlayerSource.kindFor(episodeId)
            if (kind != SourceKind.ARCHIVE) {
                val local = localLibrary.fileFor(episodeId)
                if (local != null) { loadLocal(episodeId, local); return@launch }
            }
            Log.w(PLAY, "load() episodeId=$episodeId kind=$kind")
            when (kind) {
                SourceKind.TORRENT -> loadTorrent(episodeId)
                SourceKind.ARCHIVE -> loadArchive(episodeId)
                SourceKind.WEB -> loadWeb(episodeId)
                // PlayerSource.kindFor() nunca devuelve NUC ni LOCAL (ver su propio KDoc): esta rama
                // es inalcanzable por diseño, pero el `when` exhaustivo la exige. Apunta a loadWeb()
                // -no a la loadWebRespectingPreference() desconectada- para que la afirmación del
                // KDoc de esa función ("load() llama a loadWeb directo") sea cierta para TODAS las
                // ramas, no solo la de WEB.
                SourceKind.NUC, SourceKind.LOCAL -> loadWeb(episodeId)
            }
        }
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.IO) { prefetchNext(episodeId) }
    }

    /**
     * Archivo guardado en el dispositivo. `castUrl` apunta al servidor HTTP local y NO al `file://`:
     * el Chromecast hace su propio GET desde otro dispositivo y no puede abrir una ruta del sistema
     * de archivos del celular.
     */
    private suspend fun loadLocal(episodeId: String, path: String) {
        val ep = repo.getEpisode(episodeId)
        val file = java.io.File(path)
        val castUrl = withContext(Dispatchers.IO) { runCatching { localFileServer.serve(file) }.getOrNull() }
        val item = PlayerData(
            episodeId = episodeId,
            itemId = episodeId.substringBefore("::"),
            title = ep?.displayName ?: file.name,
            subtitle = ep?.section ?: "",
            mediaUrl = "file://$path",
            castUrl = castUrl,
            artworkUrl = "",
            openingStartMs = null, openingEndMs = null, endingStartMs = null,
            kind = SourceKind.LOCAL,
        )
        val startPos = safeStartPosition(episodeId, SourceKind.LOCAL)
        _playlist.value = PlaylistData(listOf(item), 0, startPos)
        Log.w(PLAY, "loadLocal() $episodeId -> $path (cast=$castUrl)")
    }

    /**
     * Archivo guardado de un episodio de archive, listo para meterle a VLC, o null si no está.
     *
     * Sale de [LocalLibrary] y NO de la tabla `downloads` directo, que es lo que hacía antes vía
     * `repo.completedDownloadUri`. Aquella consulta miraba SOLO la columna `localUri`, que es la que
     * llenaba el `DownloadManager` del sistema; las descargas nuevas escriben la ruta en `filePath`,
     * así que `completedDownloadUri` devolvía null para todo lo bajado con el worker: se bajaban los
     * GB, la UI decía "Listo" y al dar play se streameaba igual (sin red, pantalla negra). Tampoco
     * verificaba que el archivo existiera, así que borrarlo desde los Ajustes de Android dejaba un
     * `file://` fantasma.
     *
     * `LocalLibrary.fileFor` cubre las DOS columnas (lo viejo sigue reproduciéndose), chequea
     * `exists()` y limpia la fila si el archivo se fue — con lo cual el play cae a streaming en vez
     * de a pantalla negra. Es el mismo y único resolvedor que ya usan torrent y web.
     *
     * El prefijo `file://` se agrega ACÁ: `fileFor` devuelve una ruta desnuda y `buildData` usa el
     * valor tal cual como `mediaUrl`.
     */
    private suspend fun localArchiveUri(episodeId: String): String? =
        localLibrary.fileFor(episodeId)?.let { "file://$it" }

    /** archive.org: sección completa como playlist (next/prev y autoplay nativos). */
    private suspend fun loadArchive(episodeId: String) {
        val start = repo.getEpisode(episodeId) ?: return
        val marker = repo.getSkipMarker(start.itemId)
        val episodes = repo.episodesOf(start.itemId).filter { it.section == start.section }
        val items = episodes.mapNotNull { ep ->
            buildData(ep, localArchiveUri(ep.id), marker)
        }
        if (items.isEmpty()) return
        val startIndex = items.indexOfFirst { it.episodeId == episodeId }.coerceAtLeast(0)
        val startPos = safeStartPosition(episodeId, SourceKind.ARCHIVE)
        _playlist.value = PlaylistData(items, startIndex, startPos)
    }

    /** Torrent: resuelve el .torrent/magnet, arranca el stream y emite un único ítem con la URL local. */
    private suspend fun loadTorrent(episodeId: String) {
        val src = repo.torrentSourceForEpisode(episodeId)
        val (title, url) = resolveTorrentUrl(episodeId, src) ?: return
        // Pre-buffer gate: no emitimos la playlist "lista" (que abre VLC) hasta tener la primera pieza
        // descargada. Así VLC arranca limpio (sin el broken-pipe/pantalla negra de esperar datos que
        // aún no llegan) y la espera del arranque en frío se ve con feedback (peers/%) vía prepProgress.
        preBufferHead()
        val item = PlayerData(
            episodeId = episodeId,
            itemId = episodeId.substringBefore("::"),
            title = title,
            subtitle = "",
            mediaUrl = url,
            castUrl = null,
            artworkUrl = "",
            openingStartMs = null,
            openingEndMs = null,
            endingStartMs = null,
            kind = SourceKind.TORRENT,
        )
        val startPos = safeStartPosition(episodeId, SourceKind.TORRENT)
        _playlist.value = PlaylistData(listOf(item), 0, startPos)
    }

    /**
     * DESCONECTADA desde que las descargas van al dispositivo: `load()` llama a [loadWeb] directo.
     * Se conserva porque la maquinaria de reproducción remota desde la NUC sigue completa y
     * volver a cablearla es cambiar esta única línea.
     *
     * Fuente web: consulta primero [PlaybackPreferenceStore] para saber si esta serie tiene un
     * capítulo ya bajado a la NUC y, de ser así, si hay que reproducirlo de ahí, en vivo, o
     * preguntarle al usuario (una sola vez por serie). Solo aplica a episodios de series web
     * guardadas con `addWebSeriesEpisode` (identifier `"web:series:$seriesId"` — ver
     * `ArkivRepository.addWebSeriesEpisode`); cualquier otra fuente web (películas sueltas,
     * `addWebSource`) no tiene seriesId/season/episode reales y se reproduce en vivo directo, igual
     * que siempre.
     *
     * OJO seriesId: NO es `episodeId.substringBefore("::")` a secas (eso da el identifier del ítem
     * LOCAL, `"web:series:$seriesId"`) — hay que pelarle el prefijo `"web:series:"` para que calce
     * con el `seriesId` desnudo que Task 8 guardó en `nuc_library_items` (`"anilist$anilistId"` /
     * `d.imdbId.ifBlank{"tmdb${d.id}"}`). Confirmado leyendo `addWebSeriesEpisode` en
     * `ArkivRepository.kt` y `downloadPack`/`createJob` en `AnimeShowDetailScreen`/`CineDetailScreen`.
     */
    @Suppress("unused")
    private suspend fun loadWebRespectingPreference(episodeId: String) {
        val itemIdentifier = episodeId.substringBefore("::")
        val seriesId = itemIdentifier.takeIf { it.startsWith(SERIES_ITEM_PREFIX) }
            ?.removePrefix(SERIES_ITEM_PREFIX)
        if (seriesId == null) { loadWeb(episodeId); return }
        // season/episode reales: mismo camino que ya usa resolveTorrentUrl() para el hint de pack
        // (regex sobre ep.section/displayName). Requiere que el episodio local guarde el season real
        // en `section` -- ver el fix de `addWebPack` en AnimeShowDetailScreen.kt (Task 11).
        val ctx = runCatching { repo.subtitleContextForEpisode(episodeId) }.getOrNull()
        val season = ctx?.season
        val episode = ctx?.episode
        if (season == null || episode == null) {
            Log.w(PLAY, "loadWebRespectingPreference: sin season/episode para $episodeId → en vivo directo")
            loadWeb(episodeId)
            return
        }
        when (val decision = playbackPreferenceStore.decide(seriesId, season, episode)) {
            is PlaybackDecision.Play -> when (decision.choice) {
                PlaybackChoice.NUC -> {
                    val itemId = decision.itemId
                    if (itemId != null) loadFromNuc(episodeId, itemId) else loadWeb(episodeId)
                }
                PlaybackChoice.LIVE -> loadWeb(episodeId)
            }
            is PlaybackDecision.AskFirst ->
                _askPlaybackSource.value = AskPlaybackSourceState(episodeId, seriesId, decision.itemId)
        }
    }

    /** El usuario respondió el diálogo de "¿NUC o en vivo?" (una vez por serie). */
    fun resolveAskPlaybackSource(choice: PlaybackChoice) {
        val ask = _askPlaybackSource.value ?: return
        _askPlaybackSource.value = null
        viewModelScope.launch {
            playbackPreferenceStore.remember(ask.seriesId, choice)
            when (choice) {
                PlaybackChoice.NUC -> loadFromNuc(ask.episodeId, ask.nucItemId)
                PlaybackChoice.LIVE -> loadWeb(ask.episodeId)
            }
        }
    }

    /**
     * Override manual puntual (botón del reproductor): fuerza la reproducción en vivo para ESTE
     * capítulo sin tocar la preferencia guardada de la serie (no llama a `remember`).
     */
    fun forcePlayLive(episodeId: String) {
        viewModelScope.launch { loadWeb(episodeId) }
    }

    /** NUC (arkiv-offline): arma el PlayerData con la URL de streaming directo del ítem ya bajado. */
    private suspend fun loadFromNuc(episodeId: String, itemId: Long) {
        val ep = repo.getEpisode(episodeId)
        val base = withContext(Dispatchers.IO) { arkivOfflineApi.baseUrlResolved() }
        val url = arkivOfflineApi.streamUrl(itemId, base)
        val item = PlayerData(
            episodeId = episodeId,
            itemId = episodeId.substringBefore("::"),
            title = ep?.displayName ?: "NUC",
            subtitle = ep?.section ?: "",
            mediaUrl = url,
            castUrl = url,   // /stream soporta Range directo, no necesita el rewrite de proxy que si necesita HLS
            artworkUrl = "",
            openingStartMs = null, openingEndMs = null, endingStartMs = null,
            kind = SourceKind.NUC,
        )
        val startPos = safeStartPosition(episodeId, SourceKind.NUC)
        _playlist.value = PlaylistData(listOf(item), 0, startPos)
    }

    /**
     * Fuente web: resuelve la pageUrl → stream directo vía el resolver headless de blog, y arma el
     * PlayerData con esa URL. El player unificado hereda controles/seek/cast/dlna/subs/audio. Los
     * subtítulos+headers sniffeados viajan por [webExtras] para que PlayerScreen los adjunte.
     */
    private suspend fun loadWeb(episodeId: String) {
        val pageUrl = repo.webSourceForEpisode(episodeId)
        Log.w(PLAY, "loadWeb() episodeId=$episodeId pageUrl=$pageUrl")
        if (pageUrl.isNullOrBlank()) { _error.value = "No se encontró la fuente web"; return }
        // Descartar la fuente anterior YA: el resolver tarda ~10s y, sin esto, la UI seguía mostrando
        // (y reproduciendo detrás del overlay) el video previo mientras se resuelve el nuevo.
        _playlist.value = null
        _webExtras.value = null
        _resolving.value = true
        val resolved = withContext(Dispatchers.IO) { webResolverApi.resolve(pageUrl) }
        _resolving.value = false
        Log.w(PLAY, "loadWeb() resuelto: ${if (resolved == null) "NULL (falló)" else "ok streamUrl=${resolved.streamUrl}"}")
        if (resolved == null) { _error.value = "No se pudo resolver esta fuente web"; return }
        val ep = repo.getEpisode(episodeId)
        val item = PlayerData(
            episodeId = episodeId,
            itemId = episodeId.substringBefore("::"),
            title = ep?.displayName ?: "Web",
            subtitle = ep?.section ?: "",
            mediaUrl = resolved.streamUrl,          // local: URL directa del CDN (VLC manda el Referer, rápido)
            // Casting/DLNA: el Chromecast/TV hace SU propio GET y los headers (Referer) NO viajan → usar
            // la URL PROXEADA (blog hornea el Referer server-side). Si no hay proxy, cae a la directa.
            castUrl = resolved.proxyUrl ?: resolved.streamUrl,
            artworkUrl = "",
            openingStartMs = null, openingEndMs = null, endingStartMs = null,
            kind = SourceKind.WEB,
            referer = resolved.headers["Referer"],
            userAgent = resolved.headers["User-Agent"],
            proxyUrl = resolved.proxyUrl,
        )
        _webExtras.value = WebExtras(episodeId, resolved.headers, resolved.subtitles)
        val startPos = safeStartPosition(episodeId, SourceKind.WEB)
        _playlist.value = PlaylistData(listOf(item), 0, startPos)
        Log.w(PLAY, "loadWeb() playlist publicada (1 item, startPos=$startPos) → PlayerScreen debe cargar en el controller")
    }

    /**
     * Espera a que la cabeza del archivo servido esté descargada (colchón de arranque), publicando el
     * progreso real (peers/velocidad/%) en [prepProgress]. Tope [PREBUFFER_CAP_MS] para no colgarse si
     * el torrent es muy lento; sondeo cada 250ms. Portado de `preBufferThenPlay` del TorrentPlayerScreen.
     */
    private suspend fun preBufferHead() {
        var waited = 0
        // Gate estilo Elementum/Torrest: esperar CABEZA + COLA (índice) completas, no sólo la primera pieza,
        // para que VLC no estanque leyendo el índice al abrir. Tope PREBUFFER_CAP_MS por si el torrent es lento.
        Log.i(GATE, "GATE start (esperando cabeza+cola, cap=${PREBUFFER_CAP_MS}ms)")
        while (waited < PREBUFFER_CAP_MS && !withContext(Dispatchers.IO) { torrentEngine.bufferReady() }) {
            val st = withContext(Dispatchers.IO) { torrentEngine.streamStatus() }
            _prepProgress.value = st
            if (waited % 1000 == 0) { // log 1×/s (el _prepProgress de la UI sí se refresca cada 250ms)
                val pct = withContext(Dispatchers.IO) { torrentEngine.bufferProgress() }
                Log.i(GATE, "GATE buffer=$pct% peers=${st?.peers ?: 0} dl=${st?.downloadKbps ?: 0}KB/s waited=${waited}ms")
            }
            delay(250); waited += 250
        }
        val ready = withContext(Dispatchers.IO) { torrentEngine.bufferReady() }
        Log.i(GATE, if (ready) "GATE PASSED tras ${waited}ms → abriendo VLC" else "GATE TIMEOUT tras ${waited}ms → abriendo VLC igual (buffer=${withContext(Dispatchers.IO) { torrentEngine.bufferProgress() }}%)")
        _prepProgress.value = null
    }

    /**
     * Posición de arranque validada (resume seguro): aplica la posición guardada SOLO si tiene sentido
     * retomar — más de 10s y no casi al final. Para TORRENT, además exige que esa fracción del archivo
     * ya esté descargada (baja secuencial desde el inicio: saltar en frío a una zona sin bajar stalea).
     * Si no cumple, arranca en 0. Portado de la lógica de resume de TorrentPlayerScreen.
     */
    private suspend fun safeStartPosition(episodeId: String, kind: SourceKind): Long {
        val saved = runCatching { repo.getPlayback(episodeId) }.getOrNull() ?: return 0L
        return com.arkiv.player.playback.ResumePolicy.startPosition(saved.positionMs, saved.durationMs)
            .also { Log.i(PLAY, "reanudar $episodeId ($kind): guardado=${saved.positionMs}ms → arranca en ${it}ms") }
    }

    /**
     * Traslada la resolución de torrent que antes vivía en `TorrentPlayerScreen`: elige el archivo
     * (Bytes con índice, o Magnet con hint de episodio) y espera a que el server local tenga URL.
     * Devuelve (título, url) o null (dejando el motivo en `_error`).
     */
    private suspend fun resolveTorrentUrl(episodeId: String, src: EpisodeTorrent?): Pair<String, String>? =
        when (src) {
            null -> { _error.value = "No se encontró el torrent guardado"; null }
            is EpisodeTorrent.Bytes -> {
                val meta = torrentEngine.resolveTorrent(src.data)
                if (meta == null) {
                    _error.value = "No se pudo leer el torrent"; null
                } else {
                    val title = meta.files.firstOrNull { it.index == src.fileIndex }?.name ?: meta.name
                    val url = withContext(Dispatchers.IO) {
                        runCatching { torrentEngine.startStream(meta, src.fileIndex) }.getOrNull()
                    }
                    if (url == null) { _error.value = "No se pudo iniciar el streaming"; null } else title to url
                }
            }
            is EpisodeTorrent.Magnet -> {
                // No bloqueante: arranca la descarga y espera a que llegue la metadata para levantar
                // el server. Si el episodio tiene season/episode conocidos, se pasa como hint para
                // elegir el archivo correcto dentro de un pack (en vez del más grande).
                val ctx = runCatching { repo.subtitleContextForEpisode(episodeId) }.getOrNull()
                // season=0 es válido (especiales/OVAs); solo exigimos un episodio > 0.
                val hint = ctx?.season?.let { s ->
                    ctx.episode?.let { e -> if (e > 0) EpisodeHint(s, e) else null }
                }
                withContext(Dispatchers.IO) { torrentEngine.startMagnetStream(src.uri, hint) }
                var url: String? = null
                var waited = 0
                while (url == null && waited < 180_000) {
                    url = torrentEngine.streamReadyUrl()
                    if (url == null) { delay(500); waited += 500 }
                }
                if (url == null) {
                    _error.value = "No se encontró ningún peer para este torrent"; null
                } else {
                    // El nombre del archivo que quedó servido. Sin esto el título era el literal
                    // "Torrent", que además es lo que se le muestra al Chromecast: en la TV aparecía
                    // "Torrent" en vez del nombre de lo que estás viendo.
                    (torrentEngine.servedFileName() ?: "Torrent") to url
                }
            }
        }

    private fun buildData(episode: Episode, localUri: String?, marker: SkipMarkerEntity?): PlayerData? {
        // URL http directa de archive (o null si no hay variante de streaming).
        val rawHttp = streamingVariant(episode)?.let { ArchiveUrls.download(episode.itemId, it.path) }
        // Archivo local completo (descarga terminada) -> se reproduce directo, sin proxy.
        // Streaming http de archive -> se envuelve con el proxy de caché en disco (VLC no puede
        // usar el CacheDataSource de media3), arrancándolo la primera vez.
        val mediaUrl = when {
            localUri != null -> localUri
            rawHttp != null -> {
                archiveCacheProxy.start()
                archiveCacheProxy.proxyUrl(rawHttp)
            }
            else -> return null
        }
        // Para castear/DLNA se necesita una URL alcanzable por la TV (no el proxy 127.0.0.1):
        // el mp4 compatible si existe, o la URL directa de archive como fallback.
        val castUrl = episode.castVariant?.let { ArchiveUrls.download(episode.itemId, it.path) } ?: rawHttp
        val artworkUrl = episode.thumbPath?.let { ArchiveUrls.download(episode.itemId, it) }
            ?: ArchiveUrls.thumbnail(episode.itemId)
        return PlayerData(
            episodeId = episode.id,
            itemId = episode.itemId,
            title = episode.displayName,
            subtitle = episode.section,
            mediaUrl = mediaUrl,
            castUrl = castUrl,
            artworkUrl = artworkUrl,
            openingStartMs = marker?.openingStartMs,
            openingEndMs = marker?.openingEndMs,
            endingStartMs = marker?.endingStartMs,
            kind = SourceKind.ARCHIVE,
        )
    }

    /** Precarga el SIGUIENTE episodio de la serie en segundo plano (torrent pack / web / archive). Best-effort. */
    private suspend fun prefetchNext(currentId: String) = runCatching {
        // Colchón: dejar que el actual arranque primero (torrent va en baja prioridad, no compite igual).
        kotlinx.coroutines.delay(PREFETCH_DELAY_MS)
        val next = repo.nextEpisode(currentId) ?: return@runCatching
        when (PlayerSource.kindFor(next.id)) {
            // Torrent PACK: si el actual es torrent (mismo pack) y el próximo tiene fileIndex → pre-buffer.
            // Ojo: una serie guardada vía addSeriesEpisode tiene un .torrent DISTINTO (otro infohash) por
            // episodio bajo el mismo ítem → el fileIndex del próximo indexaría un torrent DIFERENTE al que
            // está en curso. Solo pre-bufferear si es el MISMO torrent (mismos bytes → mismo handle).
            SourceKind.TORRENT -> {
                if (PlayerSource.kindFor(currentId) != SourceKind.TORRENT) return@runCatching
                val src = repo.torrentSourceForEpisode(next.id)
                val curSrc = repo.torrentSourceForEpisode(currentId)
                if (src is EpisodeTorrent.Bytes && curSrc is EpisodeTorrent.Bytes && src.data.contentEquals(curSrc.data)) {
                    Log.w(PLAY, "prefetch torrent: próximo file=${src.fileIndex} (mismo pack)")
                    torrentEngine.preBufferNextFile(src.fileIndex)
                }
            }
            // Web: pre-resolver (calienta la caché del resolver). No si el actual sigue resolviendo.
            SourceKind.WEB -> {
                if (_resolving.value) return@runCatching
                val pageUrl = repo.webSourceForEpisode(next.id)
                if (!pageUrl.isNullOrBlank()) {
                    Log.w(PLAY, "prefetch web: pre-resolviendo próximo")
                    webResolverApi.resolve(pageUrl)   // ignora el resultado; queda en caché del resolver
                }
            }
            // Archive: calentar la cabeza (Range-GET de los primeros MB de la URL del próximo).
            SourceKind.ARCHIVE -> {
                val ep = repo.getEpisode(next.id) ?: return@runCatching
                val marker = repo.getSkipMarker(ep.itemId)
                val url = buildData(ep, localArchiveUri(ep.id), marker)?.mediaUrl ?: return@runCatching
                // Descarga completada → url es file:// local (OkHttp la rechaza, trabajo inútil).
                // Streaming → url es http://127.0.0.1… (proxy): ahí sí vale la pena calentar la cabeza.
                if (url.startsWith("http", ignoreCase = true)) {
                    Log.w(PLAY, "prefetch archive: calentando cabeza")
                    warmHead(url)
                }
            }
            // PlayerSource.kindFor() nunca devuelve NUC (no depende del episodeId, sino de la
            // preferencia guardada) -- nada que precargar por esta rama.
            SourceKind.NUC -> Unit
            // Idem LOCAL: no es un kind que devuelva kindFor(), lo decide el atajo de load() en
            // tiempo de reproducción (LocalLibrary.fileFor) -- nada que precargar por acá.
            SourceKind.LOCAL -> Unit
        }
    }.onFailure { Log.w(PLAY, "prefetchNext falló: $it") }

    /** Cliente HTTP compartido para [warmHead]: evita crear un OkHttpClient (pool de hilos+conexiones) por episodio. */
    private val prefetchHttp by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** GET con Range de los primeros MB (best-effort, timeout corto) para calentar conexión/CDN. */
    private fun warmHead(url: String) {
        runCatching {
            val req = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-3145727").get().build() // 3 MB
            prefetchHttp.newCall(req).execute().use { it.body?.byteStream()?.readNBytes(3 * 1024 * 1024) }
        }
    }

    override fun onCleared() {
        prefetchJob?.cancel()
        super.onCleared()
    }

    fun setOpeningEnd(ms: Long) = updateMarker { m -> Triple(m?.openingStartMs ?: 0L, ms, m?.endingStartMs) }

    fun setEndingStart(ms: Long) = updateMarker { m -> Triple(m?.openingStartMs, m?.openingEndMs, ms) }

    fun clearMarkers() = updateMarker { Triple(null, null, null) }

    private fun updateMarker(transform: (SkipMarkerEntity?) -> Triple<Long?, Long?, Long?>) {
        val current = _playlist.value ?: return
        val itemId = current.items.firstOrNull()?.itemId ?: return
        viewModelScope.launch {
            val existing = repo.getSkipMarker(itemId)
            val (openStart, openEnd, endStart) = transform(existing)
            repo.saveSkipMarker(itemId, openStart, openEnd, endStart)
            _playlist.value = current.copy(
                items = current.items.map {
                    it.copy(openingStartMs = openStart, openingEndMs = openEnd, endingStartMs = endStart)
                },
            )
        }
    }

    private fun streamingVariant(episode: Episode): VideoVariant? =
        when (settings.streamQuality.value) {
            Quality.ORIGINAL -> episode.playbackVariant
            Quality.DERIVATIVE -> episode.derivative ?: episode.original
        }

    fun saveProgress(episodeId: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        viewModelScope.launch { repo.savePlayback(episodeId, positionMs, durationMs) }
    }

    private companion object {
        /** Identifier del ítem local para un capítulo de serie web (ver `addWebSeriesEpisode`). El
         * `seriesId` real (el que guarda Task 8 en `nuc_library_items`) es lo que queda DESPUÉS de
         * este prefijo. Se toma de [com.arkiv.player.data.SeriesItemIds] para no tener el literal
         * repetido en dos lugares que TIENEN que coincidir. */
        const val SERIES_ITEM_PREFIX = com.arkiv.player.data.SeriesItemIds.WEB_SERIES_PREFIX

        /** Tope de la espera de pre-buffer (ms): si el torrent es muy lento, se abre igual a los 30s. */
        const val PREBUFFER_CAP_MS = 30_000

        /** Tag del gate de arranque torrent (filtrar con `adb logcat -s ArkivGate`). */
        const val GATE = "ArkivGate"

        /** Tag del flujo de carga/replay del player (filtrar con `adb logcat -s ArkivPlay`). */
        const val PLAY = "ArkivPlay"

        /** Colchón antes de precargar el próximo capítulo (dar aire al arranque del actual). */
        const val PREFETCH_DELAY_MS = 8_000L
    }
}
