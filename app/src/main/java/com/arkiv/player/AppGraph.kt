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

    /** Cliente del gateway para el canal en vivo: mismos baseUrl/apiKey/accountId que [arkivApiClient]. */
    val liveApi: com.arkiv.player.data.gateway.LiveApi by lazy {
        com.arkiv.player.data.gateway.LiveApi(
            baseUrl = { settings.gatewayUrl.value },
            apiKey = { settings.arkivApiKey.value },
            http = okhttp3.OkHttpClient(),
            magisAccountId = { deviceAuth.session.value?.accountId },
        )
    }

    /**
     * Proxy HLS local del canal en vivo: firma en el aparato con respaldo en el gateway.
     *
     * El interruptor de Ajustes (`settings.liveSignRemote`) permite forzar el camino del gateway
     * para comprobar que el respaldo sigue vivo, sin esperar a que el algoritmo local se rompa de
     * verdad. Se lee con [com.arkiv.player.playback.FirmaSegunAjustes] -en CADA `firmar()`, no una
     * sola vez acá- para que cambiarlo en Ajustes tenga efecto en el próximo segmento sin
     * reiniciar la app (antes, al ser `by lazy`, el `if` de abajo se evaluaba una única vez con el
     * valor que tuviera el interruptor la primera vez que se tocaba algo en vivo).
     */
    val liveHlsProxy: com.arkiv.player.playback.LiveHlsProxy by lazy {
        val remota = com.arkiv.player.playback.FirmaDelGateway(liveApi)
        val conRespaldo = com.arkiv.player.playback.FirmaConRespaldo(
            local = com.arkiv.player.playback.FirmaLocal(),
            remota = remota,
        )
        val fuente = com.arkiv.player.playback.FirmaSegunAjustes(
            conRespaldo = conRespaldo,
            remota = remota,
            forzarRemoto = { settings.liveSignRemote.value },
        )
        com.arkiv.player.playback.LiveHlsProxy(
            fuente,
            // Tras un doble 403 irrecuperable (sesión caducada, no firma): invalida la sesión
            // cacheada de ESE canal para que el próximo abrir()/precalentar() vuelva a resolver
            // contra el gateway en vez de reusar la que ya sabemos muerta hasta 300s más.
            onSesionMuerta = { canal -> liveController.invalidar(canal) },
        )
    }

    /** Abre canales en vivo: resuelve contra [liveApi] y le entrega a VLC la URL de [liveHlsProxy]. */
    val liveController: com.arkiv.player.ui.live.LiveController by lazy {
        com.arkiv.player.ui.live.LiveController(
            resolver = { code -> liveApi.resolver(code) },
            urlPara = { sesion -> liveHlsProxy.urlPara(sesion) },
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

    /**
     * Carpeta en disco de los JPEG de frame, un solo punto para que quien escribe
     * ([frameCapturer]) y quien lee ([almacenDeFrames], desde el repositorio) usen SIEMPRE la
     * misma ruta.
     */
    private val framesDir: java.io.File by lazy { java.io.File(appContext.filesDir, "frames") }

    val almacenDeFrames: com.arkiv.player.miniaturas.AlmacenDeFrames by lazy {
        com.arkiv.player.miniaturas.AlmacenDeFrames(framesDir)
    }

    /** Captura best-effort del frame que se está viendo, para la miniatura de cada capítulo. */
    val frameCapturer: com.arkiv.player.miniaturas.FrameCapturer by lazy {
        com.arkiv.player.miniaturas.FrameCapturer(
            almacen = almacenDeFrames,
            dao = database.episodeFrameDao(),
            playbackDao = database.playbackDao(),
        )
    }

    /**
     * Único punto que sabe borrar un frame (archivo + fila), y una sola instancia para todos: se la
     * pasa por constructor a [repository] (toggle manual, progreso al 60%, y sacar un ítem de la
     * biblioteca), a [cloudSync] (progreso que llega ya visto desde otro dispositivo) y a
     * [libraryWiper] (logout) — mismo [almacenDeFrames], mismo `episodeFrameDao` que
     * [frameCapturer].
     */
    val destructorDeFrames: com.arkiv.player.miniaturas.DestructorDeFrames by lazy {
        com.arkiv.player.miniaturas.DestructorDeFrames(almacenDeFrames, database.episodeFrameDao())
    }

    /**
     * Baja best-effort el JPEG de los frames que llegaron por sync desde otro dispositivo, gemelo
     * de lectura de [frameCapturer] (mismo [almacenDeFrames], mismo `episodeFrameDao`). Necesita
     * [pbClient] y [deviceAuth] -declarados más abajo en este archivo- para el file-token de dos
     * pasos que exige el campo `img` protegido; referenciarlos acá arriba funciona igual que en
     * `arkivApiClient`/`cloudSync`: son `by lazy`, así que se resuelven recién cuando alguien pide
     * `.value`, sin importar el orden textual de las declaraciones.
     */
    val bajadorDeFrames: com.arkiv.player.miniaturas.BajadorDeFrames by lazy {
        com.arkiv.player.miniaturas.BajadorDeFrames(
            almacen = almacenDeFrames,
            dao = database.episodeFrameDao(),
            client = pbClient,
            deviceAuth = deviceAuth,
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
    val repository: ArkivRepository by lazy {
        ArkivRepository(
            database, api, tmdbApi,
            almacenDeFrames = almacenDeFrames,
            destructorDeFrames = destructorDeFrames,
            bajadorDeFrames = bajadorDeFrames,
            // El mismo scope de vida-de-app que usa todo lo demás (cloudSync, presence, ...): la
            // bajada no puede depender de que la pantalla que la disparó siga viva.
            scope = applicationScope,
        )
    }
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
        com.arkiv.player.data.catalog.mirror.MirrorApiClient(
            baseUrl = { settings.torrentApiUrl.value },
            gatewayUrl = { settings.gatewayUrl.value },
            arkivApiKey = { settings.arkivApiKey.value },
        )
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
    // `cuentaApi` referencia a `deviceAuth` solo dentro de una lambda (`deviceToken`, más abajo),
    // así que forzar `cuentaApi` acá (Task 7: el alta anónima pasa por `CuentaApi.altaAparato`)
    // no dispara una inicialización recursiva -- mismo patrón que ya usan `pbRealtime`/`pairing`
    // para resolver esta dependencia circular con `by lazy`.
    val deviceAuth: DeviceAuthManager by lazy {
        // `esTv` es una lambda y no un booleano fijo: `AppGraph` se arma temprano y
        // consultarlo en el momento del alta evita depender del orden de inicializacion.
        DeviceAuthManager(pbClient, deviceStore, cuentaApi, esTv = { DeviceType.isTelevision(appContext) })
    }
    val pbRealtime: com.arkiv.player.pocketbase.PocketBaseRealtime by lazy {
        com.arkiv.player.pocketbase.PocketBaseRealtime(token = { deviceAuth.session.value?.token })
    }
    val pairing: com.arkiv.player.pairing.PairingManager by lazy {
        com.arkiv.player.pairing.PairingManager(
            client = pbClient,
            realtime = pbRealtime,
            deviceAuth = deviceAuth,
            cuentaApi = cuentaApi,
            sesion = sesionDePersona,
            gatewayUrl = { settings.gatewayUrl.value },
            arkivApiKey = { settings.arkivApiKey.value },
            applySyncedGatewayConfig = { url, key -> settings.applySyncedGatewayConfig(url, key) },
            setTvLinked = { settings.setTvLinked(it) },
            scope = applicationScope,
        )
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
            // Favoritos y recientes de TV en vivo viajaban solo por el sync LAN; ahora también
            // por PocketBase, igual que el resto de la biblioteca (ver CloudSyncManager).
            database.liveFavoriteDao(), database.liveRecentDao(),
            database.episodeFrameDao(), almacenDeFrames,
            pbSyncClient, pbRealtime, deviceAuth, syncCursors, applicationScope,
            com.arkiv.player.cloudsync.SyncQuarantine(context),
            destructorDeFrames,
        )
    }
    val libraryWiper: com.arkiv.player.data.LibraryWiper by lazy {
        com.arkiv.player.data.LibraryWiper(
            database.itemDao(), database.playbackDao(), database.skipMarkerDao(), syncCursors,
            destructorDeFrames,
        )
    }
    /**
     * Sesión de la PERSONA (Task 1): token del gateway persistido en las prefs cifradas. Una sola
     * instancia compartida entre [accountManager] (la persiste al loguear/registrar/cerrar sesión)
     * y [cuentaApi] (la usa para autenticar los tres pedidos que no son el alta) — dos instancias
     * separadas se desincronizarían entre sí.
     */
    val sesionDePersona: com.arkiv.player.pocketbase.SesionDePersona by lazy {
        com.arkiv.player.pocketbase.SesionDePersona(pbClient, deviceStore)
    }

    /**
     * Cliente de `/v1/cuenta` (Task 2): alta con licencia + ciclo de vida de los aparatos de la
     * cuenta. [registrar] identifica al APARATO (todavía sin cuenta de persona) con el mismo token
     * que ya usa [deviceAuth]/[deviceStore] — de ahí `deviceToken` leyendo la sesión viva del
     * device en vez de `deviceStore.token()` directo, igual que [pbRealtime]/[pairing]. Task 7:
     * también lo usa [deviceAuth] mismo (`altaAparato`, alta anónima del aparato) — se referencian
     * mutuamente pero sin ciclo real: acá `deviceAuth` solo aparece dentro de la lambda
     * `deviceToken`, nunca evaluado en la construcción de este objeto.
     */
    val cuentaApi: com.arkiv.player.data.gateway.CuentaApi by lazy {
        com.arkiv.player.data.gateway.CuentaApi(
            baseUrl = { settings.gatewayUrl.value },
            apiKey = { settings.arkivApiKey.value },
            deviceToken = { deviceAuth.session.value?.token },
            sesion = sesionDePersona,
            http = okhttp3.OkHttpClient(),
        )
    }

    val accountManager: com.arkiv.player.pocketbase.AccountManager by lazy {
        com.arkiv.player.pocketbase.AccountManager(
            client = pbClient,
            deviceAuth = deviceAuth,
            store = deviceStore,
            magisLink = magisLinkClient,
            cuentaApi = cuentaApi,
            sesion = sesionDePersona,
            onAccountSwitched = { cloudSync.syncNow() },
            onLocalWipe = { libraryWiper.wipe() },
        )
    }

    /**
     * "Mis aparatos" (Task 6): lista/saca los aparatos de la cuenta. `recordIdDeEsteAparato` lee la
     * sesión viva del device -no un valor capturado- por el mismo motivo que [cuentaApi] lee
     * `deviceAuth.session.value?.token`: al construirse este grafo el bootstrap puede no haber
     * terminado. Una sola instancia (graph-level, como [accountManager]) para que el celular y la
     * TV -[com.arkiv.player.ui.settings.SettingsScreen]/[com.arkiv.player.ui.tv.TvSettingsScreen]-
     * compartan el mismo estado.
     */
    val misAparatosViewModel: com.arkiv.player.ui.settings.MisAparatosViewModel by lazy {
        com.arkiv.player.ui.settings.MisAparatosViewModel(
            cuentaApi = cuentaApi,
            sesion = sesionDePersona,
        ) { deviceAuth.session.value?.recordId }
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
