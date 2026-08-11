package com.arkiv.player

import android.content.Context
import com.arkiv.player.data.ArchiveApi
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.catalog.AniListApi
import com.arkiv.player.data.catalog.AnimeMappingRepository
import com.arkiv.player.data.catalog.AnimeSourceProvider
import com.arkiv.player.data.catalog.SimklApi
import com.arkiv.player.data.catalog.CatalogApi
import com.arkiv.player.data.catalog.CinemetaApi
import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.catalog.TorrentSearchApi
import com.arkiv.player.data.catalog.PackResolver
import com.arkiv.player.data.catalog.web.CfClearanceStore
import com.arkiv.player.data.catalog.web.HttpFetcher
import com.arkiv.player.data.catalog.web.TmdbHit
import com.arkiv.player.data.catalog.web.WebSourceDefinition
import com.arkiv.player.data.catalog.web.WebSourceEngine
import com.arkiv.player.data.catalog.web.WebSourceRegistry
import com.arkiv.player.data.catalog.web.WebTmdbMatcher
import com.arkiv.player.data.catalog.web.WebViewCloudflareSolver
import com.arkiv.player.data.SearchHistoryRepo
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.data.db.ArkivDatabase
import com.arkiv.player.data.update.ApkDownloader
import com.arkiv.player.data.update.UpdateChecker
import com.arkiv.player.data.update.UpdateInfo
import com.arkiv.player.dlna.DlnaController
import com.arkiv.player.pocketbase.DeviceAuthManager
import com.arkiv.player.pocketbase.PocketBaseClient
import com.arkiv.player.pocketbase.SecureDeviceStore
import com.arkiv.player.sync.SyncManager
import com.arkiv.player.torrent.TorrentEngine
import com.arkiv.player.torrent.TrackerListProvider
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Grafo de dependencias manual (sin Hilt): singletons de app. */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val database: ArkivDatabase by lazy { ArkivDatabase.get(appContext) }
    val api: ArchiveApi by lazy { ArchiveApi() }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val searchHistory: SearchHistoryRepo by lazy {
        SearchHistoryRepo(database.searchHistoryDao(), database.recentTitleDao())
    }

    val updateChecker: UpdateChecker by lazy {
        UpdateChecker(okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build())
    }

    private val _updateInfo = kotlinx.coroutines.flow.MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: kotlinx.coroutines.flow.StateFlow<UpdateInfo?> = _updateInfo

    val apkDownloader: ApkDownloader by lazy { ApkDownloader(appContext) }

    /**
     * Cliente del gateway unificado. La URL y la llave se leen del [settings] en CADA llamada (no
     * se capturan): así cambiarlas en Ajustes tiene efecto sin reiniciar la app.
     */
    val arkivApiClient: com.arkiv.player.data.gateway.ArkivApiClient by lazy {
        com.arkiv.player.data.gateway.ArkivApiClient(
            baseUrl = { settings.gatewayUrl.value },
            apiKey = { settings.arkivApiKey.value },
            http = okhttp3.OkHttpClient(),
            magisAccountId = { deviceAuth.session.value?.accountId },
        )
    }

    /** Chequeo inmediato de OTA: llamado por [com.arkiv.player.data.update.UpdateWorker] y al arrancar la app. */
    suspend fun checkForUpdate() {
        val info = updateChecker.check(BuildConfig.VERSION_CODE)
        if (info != null) _updateInfo.value = info
    }

    // --- Descargas al propio dispositivo (ver docs/superpowers/specs/2026-08-07-...) ---
    val httpRangeDownloader: com.arkiv.player.data.local.HttpRangeDownloader by lazy {
        com.arkiv.player.data.local.HttpRangeDownloader(
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                // OJO: `readTimeout` en OkHttp es por CADA lectura del socket, no por el request
                // completo — dispara solo si pasa este lapso sin que llegue NI UN byte. Por eso una
                // descarga de varios GB que avanza lento nunca se corta: cada chunk que llega
                // resetea el reloj. Iba en 0 (desactivado) pensando que protegía descargas largas,
                // pero eso también desactiva la protección contra un servidor que deja de mandar
                // datos sin cerrar el socket — la lectura queda colgada para siempre. Como la cola
                // procesa de a una, ESE cuelgue no traba una sola descarga: traba TODAS (el worker
                // nunca retorna, nunca se re-encola). 60s funciona como watchdog de estancamiento,
                // igual que el corte por estancamiento de TorrentDownloadStrategy (POLL_MS/
                // STALL_TIMEOUT_MS), sin arriesgar una descarga legítima que sí sigue llegando.
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        )
    }

    val localDownloads: com.arkiv.player.data.local.LocalDownloadManager by lazy {
        com.arkiv.player.data.local.LocalDownloadManager(
            appContext, database,
            wakeWorker = { com.arkiv.player.data.local.LocalDownloadWorker.schedule(it) },
            restartWorker = { com.arkiv.player.data.local.LocalDownloadWorker.restart(it) },
            deleteNucItem = { itemId -> arkivOfflineApi.deleteLibraryItem(itemId) },
        )
    }

    val localLibrary: com.arkiv.player.data.local.LocalLibrary by lazy {
        com.arkiv.player.data.local.LocalLibrary(database)
    }

    /** Captura best-effort del frame que se está viendo, para la miniatura de cada capítulo. */
    val frameCapturer: com.arkiv.player.miniaturas.FrameCapturer by lazy {
        com.arkiv.player.miniaturas.FrameCapturer(
            almacen = com.arkiv.player.miniaturas.AlmacenDeFrames(java.io.File(appContext.filesDir, "frames")),
            dao = database.episodeFrameDao(),
        )
    }

    /** Sirve el archivo local por HTTP para poder castearlo (un file:// no le llega al Chromecast). */
    val localFileServer: com.arkiv.player.playback.LocalFileServer by lazy {
        com.arkiv.player.playback.LocalFileServer(lanIp = { torrentEngine.lanIp() })
    }

    /** Una estrategia por `source` de la tabla `downloads`. */
    val downloadStrategies: Map<String, com.arkiv.player.data.local.DownloadStrategy> by lazy {
        mapOf(
            "archive" to com.arkiv.player.data.local.ArchiveDownloadStrategy(
                repository, settings, httpRangeDownloader, localDownloads::hasFreeSpaceFor,
            ),
            "torrent" to com.arkiv.player.data.local.TorrentDownloadStrategy(
                repository, torrentEngine, localDownloads::hasFreeSpaceFor,
            ),
            "magis" to com.arkiv.player.data.local.MagisDownloadStrategy(
                repository, arkivApiClient, httpRangeDownloader,
            ),
            "web" to com.arkiv.player.data.local.NucStagedStrategy(
                repository, arkivOfflineApi, httpRangeDownloader, database.downloadDao(),
            ),
        )
    }

    val dlna: DlnaController by lazy { DlnaController(appContext) }
    val repository: ArkivRepository by lazy { ArkivRepository(database, api, tmdbApi) }
    val syncManager: SyncManager by lazy { SyncManager(appContext, repository) }
    val trackerProvider: TrackerListProvider by lazy { TrackerListProvider(appContext) }
    val torrentEngine: TorrentEngine by lazy {
        TorrentEngine(appContext, extraTrackers = { trackerProvider.current() })
    }
    val catalogApi: CatalogApi by lazy { CatalogApi() }
    val aniListApi: AniListApi by lazy { AniListApi() }
    val simklApi: SimklApi by lazy {
        SimklApi(gatewayUrl = { settings.gatewayUrl.value }, arkivKey = { settings.arkivApiKey.value })
    }
    val animeMappingRepository: AnimeMappingRepository by lazy {
        AnimeMappingRepository(cacheDir = appContext.filesDir)
    }
    val animeSourceProvider: AnimeSourceProvider by lazy {
        AnimeSourceProvider(aniListApi, simklApi, animeMappingRepository, tmdbApi, torrentSearchApi)
    }
    val cinemetaApi: CinemetaApi by lazy { CinemetaApi() }
    val tmdbApi: TmdbApi by lazy {
        TmdbApi(
            gatewayUrl = { settings.gatewayUrl.value },
            arkivKey = { settings.arkivApiKey.value },
            language = "es-MX",
        )
    }
    val subtitleApi: com.arkiv.player.data.subtitles.SubtitleApi by lazy {
        com.arkiv.player.data.subtitles.SubtitleApi(
            gatewayUrl = { settings.gatewayUrl.value },
            arkivKey = { settings.arkivApiKey.value },
        )
    }
    val subtitlePrefs: com.arkiv.player.data.subtitles.SubtitlePrefs by lazy {
        com.arkiv.player.data.subtitles.SubtitlePrefs(appContext)
    }
    val archiveCacheProxy: com.arkiv.player.playback.ArchiveCacheProxy by lazy {
        com.arkiv.player.playback.ArchiveCacheProxy(java.io.File(appContext.cacheDir, "archive-cache"))
    }
    // --- Fetcher HTTP compartido (con resolución de Cloudflare) usado por la capa web on-device ---
    // Store único compartido entre el solver y el fetcher: así el short-circuit de caché del
    // solver (store.get(host)) aplica también cuando el fetcher entra directo por el cf_clearance
    // ya resuelto, en vez de cada uno cachear por su lado con TTLs independientes.
    private val cfStore by lazy { CfClearanceStore() }
    private val cloudflareSolver by lazy {
        WebViewCloudflareSolver(appContext, enabled = { settings.cloudflareSolverEnabled.value }, store = cfStore)
    }
    private val providerFetcher by lazy { HttpFetcher(solver = cloudflareSolver, store = cfStore) }

    val mirrorApiClient: com.arkiv.player.data.catalog.mirror.MirrorApiClient by lazy {
        com.arkiv.player.data.catalog.mirror.MirrorApiClient(baseUrl = { settings.torrentApiUrl.value })
    }

    val torrentSearchApi: TorrentSearchApi by lazy {
        TorrentSearchApi(mirror = mirrorApiClient)
    }

    val packResolver: PackResolver by lazy {
        PackResolver(torrentSearchApi, torrentEngine)
    }

    // --- Capa de fuentes web on-device (scraping estilo Alfa) ---
    // Definiciones activas de webs. Igual patrón que providerDefinitions: bundled (assets, sin red)
    // + refresh async desde blog (hot-update). @Volatile para que el refresh sea visible al engine.
    @Volatile private var webSourceDefinitions: List<WebSourceDefinition> = loadBundledWebSources()

    private fun loadBundledWebSources(): List<WebSourceDefinition> = runCatching {
        val json = appContext.assets.open("web_sources.json").bufferedReader().use { it.readText() }
        WebSourceRegistry.parseDefinitions(json)
    }.getOrDefault(emptyList())

    val webSourceEngine: WebSourceEngine by lazy {
        WebSourceEngine(providerFetcher) { webSourceDefinitions }
    }

    /** Cliente del resolver headless de blog (pageUrl web → stream reproducible). */
    val webResolverApi: com.arkiv.player.data.catalog.web.WebResolverApi by lazy {
        com.arkiv.player.data.catalog.web.WebResolverApi(baseUrl = { settings.webResolverUrl.value })
    }

    /** Cliente de arkiv-offline (NUC de casa): jobs de descarga + biblioteca ya bajada. */
    val arkivOfflineApi: com.arkiv.player.data.offline.ArkivOfflineApi by lazy {
        com.arkiv.player.data.offline.ArkivOfflineApi(
            lanBaseUrl = { settings.nucLanBaseUrl.value },
            tunnelBaseUrl = { settings.nucTunnelBaseUrl.value },
            apiKey = { settings.nucApiKey.value },
        )
    }

    /** Progreso casi en tiempo real (SSE+poll) de un job de arkiv-offline. Usado por la pantalla
     * de Descargas del NUC (Task 9). */
    val nucJobEvents: com.arkiv.player.data.offline.NucJobEvents by lazy {
        com.arkiv.player.data.offline.NucJobEvents(arkivOfflineApi, apiKey = { settings.nucApiKey.value })
    }

    val playbackPreferenceStore: com.arkiv.player.data.offline.PlaybackPreferenceStore by lazy {
        com.arkiv.player.data.offline.PlaybackPreferenceStore(database.seriesPlaybackPrefDao(), database.nucLibraryItemDao())
    }

    val webTmdbMatcher: WebTmdbMatcher by lazy {
        WebTmdbMatcher(lookup = { type, query ->
            runCatching {
                tmdbApi.search(type, query).map { TmdbHit(it.id, it.title, it.year, it.posterUrl) }
            }.getOrDefault(emptyList())
        })
    }

    /** Baja web_sources.json de blog (async) y mergea sobre las bundled. Si falla, quedan las bundled. */
    private suspend fun refreshRemoteWebSources() {
        runCatching {
            val remote = okhttp3.OkHttpClient.Builder()
                .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build()
                .newCall(okhttp3.Request.Builder().url(settings.webSourcesUrl.value).build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null } ?: return@runCatching
            val merged = WebSourceRegistry.merge(webSourceDefinitions, WebSourceRegistry.parseDefinitions(remote))
            if (merged.isNotEmpty()) webSourceDefinitions = merged
        }
    }

    val applicationScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    private val buscadorDeCapitulos by lazy {
        com.arkiv.player.data.nuevos.BuscadorDeCapitulos(
            repo = repository,
            itemDao = database.itemDao(),
            gateway = arkivApiClient,
        )
    }

    /**
     * Busca capítulos nuevos de las series que se están viendo, con freno de repetición.
     *
     * El freno hace falta porque `Application.onCreate` corre muchas veces por día —basta con
     * salir de la app y volver a entrar— y cada pasada cuesta red (para web, una búsqueda por
     * capítulo candidato). Una vez cada [HORAS_ENTRE_BUSQUEDAS] horas alcanza de sobra: los
     * capítulos no salen más seguido que eso.
     */
    suspend fun buscarCapitulosNuevos() {
        val prefs = appContext.getSharedPreferences("arkiv_nuevos", android.content.Context.MODE_PRIVATE)
        val ultima = prefs.getLong(KEY_ULTIMA_BUSQUEDA, 0L)
        val ahora = System.currentTimeMillis()
        if (ahora - ultima < HORAS_ENTRE_BUSQUEDAS * 60 * 60 * 1000L) return
        // Se sella ANTES de buscar: si la búsqueda tarda y el usuario cierra y reabre la app en el
        // medio, no arrancan dos pasadas pisándose contra las mismas fuentes.
        prefs.edit().putLong(KEY_ULTIMA_BUSQUEDA, ahora).apply()
        buscadorDeCapitulos.buscar()
    }

    /**
     * Señal para resetear la búsqueda del catálogo al entrar desde otra pestaña. La emite el click
     * en la pestaña "Catálogo" (que solo existe estando en una pestaña, nunca dentro de un detalle),
     * así que volver de un detalle con "atrás" NO la dispara y preserva la búsqueda.
     */
    val catalogResetSignal = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** CastContext de Chromecast, o null si Google Play Services no está disponible. */
    val castContext: CastContext? by lazy {
        runCatching { CastContext.getSharedInstance(appContext) }.getOrNull()
    }

    /** Dueño del CastPlayer con vida de app: sin esto, salir del reproductor corta el casteo. */
    val castSession: com.arkiv.player.cast.CastSessionManager? by lazy {
        castContext?.let {
            com.arkiv.player.cast.CastSessionManager(
                it,
                repository,
                applicationScope,
                onSessionEnded = {
                    // Sin esto el transcodificador sigue vivo después de desconectar: CPU y puerto
                    // retenidos sin nadie del otro lado.
                    castTranscoder.stop()
                },
                awaitSourceReady = { request ->
                    // Solo hay que esperar cuando lo que se manda es el stream que estamos
                    // generando; una URL de archive.org ya está servida desde siempre.
                    if (request.uri != castTranscoder.activeUrl) {
                        true
                    } else {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            castTranscoder.awaitReady()
                        }
                    }
                },
            )
        }
    }

    /**
     * Transcodifica el audio que el Chromecast no decodifica (AC-3, DTS…). Vive acá, con el resto
     * del casteo, porque tiene que sobrevivir a que se cierre el reproductor igual que [castSession].
     */
    val castTranscoder: com.arkiv.player.cast.CastTranscoder by lazy {
        com.arkiv.player.cast.CastTranscoder(appContext)
    }

    val pbClient: PocketBaseClient by lazy { PocketBaseClient() }
    val deviceStore: SecureDeviceStore by lazy { SecureDeviceStore(appContext) }
    val deviceAuth: DeviceAuthManager by lazy { DeviceAuthManager(pbClient, deviceStore) }
    val pbRealtime: com.arkiv.player.pocketbase.PocketBaseRealtime by lazy {
        com.arkiv.player.pocketbase.PocketBaseRealtime(token = { deviceAuth.session.value?.token })
    }
    val pairing: com.arkiv.player.pairing.PairingManager by lazy {
        com.arkiv.player.pairing.PairingManager(pbClient, pbRealtime, deviceStore, deviceAuth, settings, applicationScope)
    }
    val remoteController: com.arkiv.player.remote.RemoteController by lazy {
        com.arkiv.player.remote.RemoteController(syncManager, pbClient, pbRealtime, deviceAuth, settings, applicationScope)
    }
    val syncCursors: com.arkiv.player.cloudsync.SyncCursors by lazy {
        com.arkiv.player.cloudsync.SyncCursors(appContext)
    }
    val pbSyncClient: com.arkiv.player.cloudsync.PbSyncClient by lazy {
        com.arkiv.player.cloudsync.PbSyncClient(pbClient, deviceAuth)
    }
    val cloudSync: com.arkiv.player.cloudsync.CloudSyncManager by lazy {
        com.arkiv.player.cloudsync.CloudSyncManager(
            database.itemDao(), database.playbackDao(), database.skipMarkerDao(),
            pbSyncClient, pbRealtime, deviceAuth, syncCursors, applicationScope,
            com.arkiv.player.cloudsync.SyncQuarantine(context),
        )
    }
    val libraryWiper: com.arkiv.player.data.LibraryWiper by lazy {
        com.arkiv.player.data.LibraryWiper(
            database.itemDao(), database.playbackDao(), database.skipMarkerDao(), syncCursors,
        )
    }
    val accountManager: com.arkiv.player.pocketbase.AccountManager by lazy {
        com.arkiv.player.pocketbase.AccountManager(
            client = pbClient,
            deviceAuth = deviceAuth,
            store = deviceStore,
            magisLink = magisLinkClient,
            onAccountSwitched = { cloudSync.syncNow() },
            onLocalWipe = { libraryWiper.wipe() },
        )
    }

    /** Vincular/desvincular la cuenta de Magis con la cuenta Arkiv (mismas fuentes de baseUrl/apiKey que [arkivApiClient]). */
    val magisLinkClient: com.arkiv.player.pocketbase.MagisLinkClient by lazy {
        com.arkiv.player.pocketbase.MagisLinkClient(
            baseUrl = { settings.gatewayUrl.value },
            apiKey = { settings.arkivApiKey.value },
            accountId = { deviceAuth.session.value?.accountId },
        )
    }

    val presence: com.arkiv.player.presence.PresenceManager by lazy {
        com.arkiv.player.presence.PresenceManager(pbClient, deviceAuth, applicationScope)
    }

    /** Solo tiene sentido en el TV: es quien reproduce y publica su estado. */
    val nowPlayingPublisher: com.arkiv.player.remote.NowPlayingPublisher by lazy {
        com.arkiv.player.remote.NowPlayingPublisher(pbClient, deviceAuth, repository, applicationScope)
    }

    /** Solo tiene sentido en el celu: es quien mira lo que reproduce el TV. */
    val tvNowPlaying: com.arkiv.player.remote.TvNowPlayingRepository by lazy {
        com.arkiv.player.remote.TvNowPlayingRepository(
            pbClient, deviceAuth, { remoteController.tvPaired.value }, applicationScope,
        )
    }

    /** Única fuente de verdad de la barra del miniplayer (TV o Chromecast). */
    val nowPlayingCoordinator: com.arkiv.player.remote.NowPlayingCoordinator by lazy {
        com.arkiv.player.remote.NowPlayingCoordinator(tvNowPlaying, castSession, repository, applicationScope)
    }

    init {
        applicationScope.launch {
            deviceAuth.ensureBootstrapped()
            // Arrancar el sync de biblioteca + presencia tras el bootstrap (reintentan si no hay sesión aún).
            cloudSync.start()
            presence.start()
            if (com.arkiv.player.DeviceType.isTelevision(appContext)) {
                nowPlayingPublisher.start()
            } else {
                tvNowPlaying.start()
            }
        }
        // Hot-update de fuentes web on-device desde blog (mismo patrón, misma tolerancia a fallos).
        applicationScope.launch { refreshRemoteWebSources() }
        // Hot-update de la lista de trackers (ngosang) para mejor descubrimiento de peers (best-effort).
        applicationScope.launch { trackerProvider.refresh() }
        // CastPlayer/CastContext exigen el hilo principal. Forzamos su construcción ahí para que el
        // manager exista desde el arranque (y adopte una sesión ya viva) sin depender de quién lo
        // toque primero.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            castSession
            // El coordinador de la barra toca castSession, así que se arranca ACÁ y no en el launch
            // de arriba: ése corre en IO y construir el CastSessionManager fuera del hilo principal
            // revienta el check explícito de su init.
            if (!com.arkiv.player.DeviceType.isTelevision(appContext)) {
                nowPlayingCoordinator.start()
            }
        }
    }

    /**
     * DEBUG: hace browse por-canal (cada kind declarado) para validar EN VIVO qué definiciones web
     * devuelven filas contra su sitio real (con el fetcher + WebView-solver de Cloudflare). Devuelve
     * líneas tipo "web-diag <id> [baseUrl]: movie=N tv=M". Se dispara desde el WebDiagReceiver (debug).
     */
    suspend fun webDiagnostics(only: Set<String>? = null): List<String> {
        val out = ArrayList<String>()
        for (def in webSourceDefinitions) {
            if (!def.enabled) continue                       // no gastar fetches en dead-ends
            if (only != null && def.id !in only) continue    // filtro opcional por id
            val backend = com.arkiv.player.data.catalog.web.WebSourceBackend(def, providerFetcher)
            val parts = ArrayList<String>()
            val kinds = (def.api?.browse ?: def.browse).keys   // modo API o HTML
            if (kinds.isEmpty()) {
                parts.add("sin browse")
            } else {
                for (kind in kinds) {
                    val n = runCatching { backend.browse(kind, 1) }.getOrDefault(emptyList()).size
                    parts.add("$kind=$n")
                }
            }
            // Prueba de búsqueda (lo que usa la HOJA DE FUENTES): busca un título conocido.
            val ctx = com.arkiv.player.data.catalog.providers.SearchContext(
                titles = listOf("Batman"),
                type = com.arkiv.player.data.catalog.providers.ContentType.MOVIE,
                year = "2022",
            )
            val s = runCatching { backend.search(ctx) }.getOrDefault(emptyList()).size
            parts.add("search(Batman)=$s")
            out.add("web-diag ${def.id} [${def.baseUrl}]: ${parts.joinToString("  ")}")
        }
        return out
    }

    /**
     * DEBUG: baja el HTML crudo del listado de cada canal (browse "movie", o el primer kind) probando
     * baseUrl+hostAlt, y lo guarda en [dir]/<id>.html para inspeccionar los selectores/dominios reales.
     * Devuelve líneas de estado. Sirve para arreglar canales rotos contra el HTML vivo.
     */
    suspend fun webDiagnosticsDump(dir: java.io.File, only: Set<String>? = null): List<String> {
        dir.mkdirs()
        val out = ArrayList<String>()
        for (def in webSourceDefinitions) {
            if (!def.enabled) continue
            if (only != null && def.id !in only) continue
            val browseMap = def.api?.browse ?: def.browse   // modo API o HTML
            val kind = if (browseMap.containsKey("movie")) "movie" else browseMap.keys.firstOrNull()
            if (kind == null) { out.add("dump ${def.id}: sin browse"); continue }
            val path = browseMap.getValue(kind).replace("{page}", "1")
            val bases = listOf(def.baseUrl) + def.hostAlt
            var html: String? = null
            var usedBase = ""
            for (base in bases) {
                usedBase = base
                html = runCatching { providerFetcher.fetch(base.trimEnd('/') + path, def.charset) }.getOrNull()
                if (!html.isNullOrBlank()) break
            }
            if (html.isNullOrBlank()) {
                out.add("dump ${def.id}: SIN RESPUESTA (probados: ${bases.joinToString()})")
            } else {
                runCatching { java.io.File(dir, "${def.id}.html").writeText(html) }
                out.add("dump ${def.id}: OK ${html.length} bytes (base=$usedBase)")
            }
        }
        return out
    }

    /**
     * DEBUG: baja el HTML de URLs ARBITRARIAS con el fetcher real (incluye WebView-solver de
     * Cloudflare). Sirve para fingerprintear páginas de serie de sitios con Cloudflare, que no se
     * pueden fetchear con curl desde fuera. Guarda en [dir]/url{i}_<host>.html.
     */
    suspend fun webFetchDump(dir: java.io.File, urls: List<String>): List<String> {
        dir.mkdirs()
        val out = ArrayList<String>()
        urls.forEachIndexed { i, url ->
            val html = runCatching { providerFetcher.fetch(url, "utf-8") }.getOrNull()
            val slug = url.substringAfter("://").replace(Regex("[^A-Za-z0-9]+"), "_").take(70)
            val name = "url${i}_$slug.html"
            if (html.isNullOrBlank()) {
                out.add("fetch $url: SIN RESPUESTA")
            } else {
                runCatching { java.io.File(dir, name).writeText(html) }
                out.add("fetch $url: OK ${html.length}B -> $name")
            }
        }
        return out
    }

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        /** Cada cuánto, como mucho, se buscan capítulos nuevos. Ver [buscarCapitulosNuevos]. */
        private const val HORAS_ENTRE_BUSQUEDAS = 6L
        private const val KEY_ULTIMA_BUSQUEDA = "ultima_busqueda_ms"

        fun from(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context).also { instance = it }
            }
    }
}
