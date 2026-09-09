package com.arkiv.player

import android.content.Context
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.catalog.AniListApi
import com.arkiv.player.data.catalog.AnimeMappingRepository
import com.arkiv.player.data.catalog.SimklApi
import com.arkiv.player.data.catalog.TmdbApi
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
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Grafo de dependencias manual (sin Hilt): singletons de app. */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val database: ArkivDatabase by lazy { ArkivDatabase.get(appContext) }
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

    private val _hayInternet = kotlinx.coroutines.flow.MutableStateFlow(true)
    val hayInternet: kotlinx.coroutines.flow.StateFlow<Boolean> = _hayInternet

    private val monitorDeRed: android.net.ConnectivityManager.NetworkCallback by lazy {
        object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { _hayInternet.value = true }
            override fun onLost(network: android.net.Network) {
                val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java)
                val activa = cm?.activeNetwork
                if (activa == null) _hayInternet.value = false
            }
            override fun onUnavailable() { _hayInternet.value = false }
        }.also { cb ->
            runCatching {
                val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java)
                    ?: return@runCatching
                // Estado inicial: verificar si ya hay red al arrancar
                val activa = cm.activeNetwork
                val caps = activa?.let { cm.getNetworkCapabilities(it) }
                _hayInternet.value = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                cm.registerDefaultNetworkCallback(cb)
            }.onFailure { android.util.Log.w("ArkivRed", "monitorDeRed: ${it.message}") }
        }
    }

    /** Inicia el monitor de conectividad; llamar desde Application.onCreate. */
    fun iniciarMonitorDeRed() { monitorDeRed }  // acceso fuerza la inicialización del lazy

    val apkDownloader: ApkDownloader by lazy { ApkDownloader(appContext) }

    /**
     * `OkHttpClient` COMPARTIDO para todo lo que hable con el gateway unificado (Task 7b): antes
     * había seis `OkHttpClient()` sueltos (acá abajo x3, `PlayerViewModel.gatewayClient`,
     * `MagisLinkClient`, `PocketBaseClient`), así que un rechazo de identidad real (licencia
     * revocada, aparato sacado desde "Mis aparatos") no tenía quién lo mirara fuera de las dos
     * pantallas que ya implementan la regla a mano (`EntradaViewModel`, `MisAparatosViewModel`).
     * `InterceptorDeSesion` es ese punto único: cierra [sesionDePersona] SOLO ante un 401/403 del
     * gateway con un `codigo` de rechazo de identidad real -nunca ante un fallo de transporte, ver
     * su KDoc-. `PocketBaseClient` no lo usa a propósito: habla con OTRO host (PocketBase, no el
     * gateway), así que el interceptor nunca haría nada ahí -el check de host de
     * `InterceptorDeSesion` ya lo filtraría solo-, y esa sesión la gobierna
     * [com.arkiv.player.pocketbase.SesionDePersona.refrescar], que resuelve la misma distinción por
     * su cuenta.
     */
    val httpGateway: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .addInterceptor(
                com.arkiv.player.data.gateway.InterceptorDeSesion(
                    gatewayUrl = { settings.gatewayUrl.value },
                    sesion = sesionDePersona,
                ),
            )
            // TOPE A LA LLAMADA ENTERA. Los timeouts sueltos de OkHttp se reinician con cada byte,
            // así que una respuesta que llega a cuentagotas —o que se queda a medias detrás del
            // túnel de Cloudflare -- no vence NUNCA. Se midió abriendo una película: el gateway
            // contestó su 200 y la app se quedó dos minutos con el spinner, sin error y sin nada
            // que reintentar, porque la corrutina del resolve nunca volvió.
            //
            // 45 s y no menos: `/v1/search` se gasta sus 15 s de presupuesto y todavía tiene que
            // devolver el cuerpo. Lo que importa no es cortar rápido, es que corte.
            .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** [httpGateway] con los timeouts cortos que ya usaban `TmdbApi`/`SimklApi`/`SubtitleApi`/
     *  `MirrorApiClient` por default (pedidos JSON cortos, no streaming) -se explicita acá para no
     *  perder ese ajuste al pasar de sus `OkHttpClient` por default a este compartido. */
    val httpGatewayCorto: okhttp3.OkHttpClient by lazy {
        httpGateway.newBuilder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    // --- Magis directo (sub-proyecto 2A) ------------------------------------------------------
    //
    // Todo el protocolo del portal vive en `data/magis`. El `sn` del device sale del store en CADA
    // llamada (no se captura): lo acuña `MagisSession` en caliente la primera vez, y el body de esa
    // misma activación ya tiene que llevarlo.

    internal val magisStore: com.arkiv.player.data.magis.MagisCredentialStore by lazy {
        com.arkiv.player.data.magis.EncryptedMagisCredentialStore(appContext)
    }

    private val magisPortal: com.arkiv.player.data.magis.MagisPortalClientLike by lazy {
        com.arkiv.player.data.magis.MagisPortalClient(
            crypto = com.arkiv.player.data.magis.MagisCrypto(BuildConfig.IPTV_3DES_KEY),
            hosts = BuildConfig.IPTV_HOSTS.split(",").map { it.trim() }.filter { it.isNotBlank() },
            appId = BuildConfig.IPTV_APP_ID,
            apkVersion = BuildConfig.IPTV_APK_VERSION,
            snProvider = { magisStore.leerSesion()?.sn.orEmpty() },
            http = httpGateway,
        )
    }

    internal val magisSession: com.arkiv.player.data.magis.MagisSession by lazy {
        com.arkiv.player.data.magis.MagisSession(magisPortal, magisStore)
    }

    private val magisCatalog: com.arkiv.player.data.magis.MagisCatalog by lazy {
        com.arkiv.player.data.magis.MagisCatalog(magisPortal, magisSession)
    }

    /** De dónde salen los títulos que la app busca y reproduce: el portal, directo. */
    val fuenteDeContenido: com.arkiv.player.data.gateway.FuenteDeContenido by lazy {
        com.arkiv.player.data.magis.MagisFuente(
            catalogo = magisCatalog,
            resolucion = com.arkiv.player.data.magis.MagisResolve(magisPortal, magisSession),
            tmdb = tmdbApi,
        )
    }

    internal val magisLive: com.arkiv.player.data.magis.MagisLive by lazy {
        com.arkiv.player.data.magis.MagisLive(magisPortal, magisSession)
    }

    val catalogoDeVivo: com.arkiv.player.data.gateway.LiveCatalogGateway by lazy {
        com.arkiv.player.data.magis.MagisLiveCatalog(magisCatalog, magisPortal, magisSession)
    }

    /**
     * Cliente del gateway unificado. La URL se lee del [settings] en CADA llamada (no se
     * captura): así cambiarla en Ajustes tiene efecto sin reiniciar la app.
     *
     * Ya NO sirve contenido (búsqueda, reproducción ni capítulos): eso lo da [fuenteDeContenido]
     * hablándole al portal directo. Queda para lo que sigue siendo del servidor — la trivia
     * (excepción permanente) y los marcadores de intro.
     */
    val arkivApiClient: com.arkiv.player.data.gateway.ArkivApiClient by lazy {
        com.arkiv.player.data.gateway.ArkivApiClient(
            baseUrl = { settings.gatewayUrl.value },
            http = httpGateway,
            // Task 8 (Paso 2): mismas fuentes que ya usa `cuentaApi` para las dos cabeceras de
            // sesión -- no una lectura nueva/paralela del store.
            personToken = { sesionDePersona.token() },
            deviceToken = { deviceAuth.session.value?.token },
        )
    }

    /** Cliente del gateway para el canal en vivo: mismos baseUrl/apiKey que [arkivApiClient]. */
    val liveApi: com.arkiv.player.data.gateway.LiveApi by lazy {
        com.arkiv.player.data.gateway.LiveApi(
            baseUrl = { settings.gatewayUrl.value },
            http = httpGateway,
            personToken = { sesionDePersona.token() },
            deviceToken = { deviceAuth.session.value?.token },
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
     * biblioteca) y a [libraryWiper] (logout) — mismo [almacenDeFrames], mismo `episodeFrameDao`
     * que [frameCapturer].
     */
    val destructorDeFrames: com.arkiv.player.miniaturas.DestructorDeFrames by lazy {
        com.arkiv.player.miniaturas.DestructorDeFrames(almacenDeFrames, database.episodeFrameDao())
    }

    /** Sirve el archivo local por HTTP para poder castearlo (un file:// no le llega al Chromecast). */
    val localFileServer: com.arkiv.player.playback.LocalFileServer by lazy {
        com.arkiv.player.playback.LocalFileServer(lanIp = { lanIp() })
    }

    /** IP del aparato en la LAN (ver [com.arkiv.player.playback.LanIp]): la necesitan el proxy de
     *  canal en vivo y el cast transcodificado para que un renderer en la LAN pueda alcanzarlos. */
    fun lanIp(): String? = com.arkiv.player.playback.LanIp.current(appContext)

    /**
     * Una estrategia por `source` de la tabla `downloads`. Sin entrada para "web" a propósito: la
     * fuente web se borró en esta rama (regla del branch, "cero servidor propio") y
     * `NucStagedStrategy` (que existía solo para servirla, hablando con el servidor NUC/arkiv-offline)
     * se borró en la poda de NUC (Task 8) — una fila vieja con `source="web"` (de antes de este
     * branch) ahora falla con gracia en vez de disparar esa llamada de red (ver
     * `LocalDownloadWorker.doWork()`, que ya trata una entrada ausente como "Fuente no soportada").
     *
     * Tampoco hay entrada para "archive": `ArchiveDownloadStrategy` se borró junto con el resto de
     * archive.org en esta poda (llamaba a `ArchiveUrls.download`, red directa a archive.org — contra
     * la regla del branch). Una fila vieja con `source="archive"` cae al mismo camino de gracia que
     * "web".
     *
     * Ni para "ditu": `DituDownloadStrategy` se borró junto con el resto de Ditu/Caracol Play en
     * esta poda (Ditu vuelve en el sub-proyecto 3 con un cliente directo). Una fila vieja con
     * `source="ditu"` cae al mismo camino de gracia.
     */
    val downloadStrategies: Map<String, com.arkiv.player.data.local.DownloadStrategy> by lazy {
        mapOf(
            "magis" to com.arkiv.player.data.local.MagisDownloadStrategy(
                repository, fuenteDeContenido, httpRangeDownloader,
            ),
        )
    }

    val dlna: DlnaController by lazy { DlnaController(appContext) }

    /** Avisa al gateway cuando algo pasa a visto, para "Para ti" (ver el doc de la clase). */
    val avisadorDeRecomendaciones: com.arkiv.player.data.gateway.AvisadorDeRecomendaciones by lazy {
        com.arkiv.player.data.gateway.AvisadorDeRecomendaciones(arkivApiClient)
    }

    val repository: ArkivRepository by lazy {
        ArkivRepository(
            database, tmdbApi,
            almacenDeFrames = almacenDeFrames,
            destructorDeFrames = destructorDeFrames,
            avisadorDeRecomendaciones = avisadorDeRecomendaciones,
            // El mismo scope de vida-de-app que usa todo lo demás: el aviso de recomendaciones no
            // puede depender de que la pantalla que lo disparó siga viva.
            scope = applicationScope,
        )
    }
    val aniListApi: AniListApi by lazy { AniListApi() }
    val simklApi: SimklApi by lazy {
        SimklApi(
            gatewayUrl = { settings.gatewayUrl.value },
            client = httpGatewayCorto,
            personToken = { sesionDePersona.token() },
            deviceToken = { deviceAuth.session.value?.token },
        )
    }
    val animeMappingRepository: AnimeMappingRepository by lazy {
        AnimeMappingRepository(cacheDir = appContext.filesDir)
    }
    val tmdbApi: TmdbApi by lazy {
        TmdbApi(
            language = "es-MX",
            client = httpGatewayCorto,
        )
    }
    val subtitleApi: com.arkiv.player.data.subtitles.SubtitleApi by lazy {
        com.arkiv.player.data.subtitles.SubtitleApi(
            gatewayUrl = { settings.gatewayUrl.value },
            client = httpGatewayCorto,
            personToken = { sesionDePersona.token() },
            deviceToken = { deviceAuth.session.value?.token },
        )
    }
    val subtitlePrefs: com.arkiv.player.data.subtitles.SubtitlePrefs by lazy {
        com.arkiv.player.data.subtitles.SubtitlePrefs(appContext)
    }
    /**
     * Vigila los cambios de red para que [archiveCacheProxy] abandone las conexiones que quedaron
     * atadas a la red anterior. Se guarda la referencia aunque nadie la use: el vigilante vive lo
     * que vive el proceso, igual que el proxy, y tenerlo a mano deja poder pararlo si algún día
     * hace falta. Ver [com.arkiv.player.playback.CambioDeRed].
     */
    private var vigilanteDeRed: com.arkiv.player.playback.VigilanteDeRed? = null

    val archiveCacheProxy: com.arkiv.player.playback.ArchiveCacheProxy by lazy {
        com.arkiv.player.playback.ArchiveCacheProxy(java.io.File(appContext.cacheDir, "archive-cache"))
            .also { proxy ->
                vigilanteDeRed = com.arkiv.player.playback.VigilanteDeRed(appContext) { motivo ->
                    proxy.abandonarConexiones(motivo)
                }.apply { empezar() }
            }
    }
    val applicationScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    /**
     * Agrega a la biblioteca lo que se elige en la fila "Para ti" del inicio. Necesita el gateway
     * además del repositorio: una recomendación de serie trae el ref de la temporada, y los
     * capítulos hay que pedírselos a `/v1/episodes`.
     */
    val agregadorDeRecomendaciones by lazy {
        com.arkiv.player.data.recomendaciones.AgregadorDeRecomendaciones(
            repo = repository,
            gateway = fuenteDeContenido,
        )
    }

    private val buscadorDeCapitulos by lazy {
        com.arkiv.player.data.nuevos.BuscadorDeCapitulos(
            repo = repository,
            itemDao = database.itemDao(),
            gateway = fuenteDeContenido,
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
    // no dispara una inicialización recursiva -- se resuelve esta dependencia circular con
    // `by lazy` igual que el resto de este grafo.
    val deviceAuth: DeviceAuthManager by lazy {
        // `esTv` es una lambda y no un booleano fijo: `AppGraph` se arma temprano y
        // consultarlo en el momento del alta evita depender del orden de inicializacion.
        DeviceAuthManager(pbClient, deviceStore, cuentaApi, esTv = { DeviceType.isTelevision(appContext) })
    }
    val libraryWiper: com.arkiv.player.data.LibraryWiper by lazy {
        com.arkiv.player.data.LibraryWiper(
            database.itemDao(), database.playbackDao(), database.skipMarkerDao(),
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
     * device en vez de `deviceStore.token()` directo. Task 7:
     * también lo usa [deviceAuth] mismo (`altaAparato`, alta anónima del aparato) — se referencian
     * mutuamente pero sin ciclo real: acá `deviceAuth` solo aparece dentro de la lambda
     * `deviceToken`, nunca evaluado en la construcción de este objeto.
     */
    val cuentaApi: com.arkiv.player.data.gateway.CuentaApi by lazy {
        com.arkiv.player.data.gateway.CuentaApi(
            baseUrl = { settings.gatewayUrl.value },
            deviceToken = { deviceAuth.session.value?.token },
            sesion = sesionDePersona,
            // `InterceptorDeSesion` cierra la sesión ante un 401/403 de identidad real igual que ya
            // hacen los llamadores (`EntradaViewModel.manejarErrorDeCuenta`,
            // `MisAparatosViewModel.manejarErrorDeSesion`) al recibir el `ErrorDeCuenta` -llamar
            // `cerrar()` dos veces es inofensivo (`SesionDePersona.cerrar` es idempotente)-, y
            // `/v1/cuenta/registrar` queda afuera por la guarda de ruta del interceptor.
            http = httpGateway,
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
            // Sin cloud sync (Task 5 de esta poda) la biblioteca es 100% local: no hay ningún
            // historial anónimo remoto que fusionar al loguearse, así que este callback queda en
            // no-op. Se mantiene el parámetro (en vez de sacarlo de `AccountManager`) porque el
            // login todavía depende del ORDEN en que se llama -- ver el KDoc de `login()`.
            onAccountSwitched = {},
            // Task 10: junto con vaciar la biblioteca local, se olvida el "Ahora no" a la oferta de
            // vincular Magis -ver el KDoc de SettingsStore.magisOfertaDescartada sobre por qué acá y
            // no en LibraryWiper (ese vive en la capa de datos y no conoce SettingsStore, que es UI).
            onLocalWipe = { libraryWiper.wipe(); settings.setMagisOfertaDescartada(false) },
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

    /** Vincular/desvincular la cuenta de Magis con la cuenta Arkiv (misma fuente de baseUrl que [arkivApiClient]). */
    val magisLinkClient: com.arkiv.player.pocketbase.MagisLinkClient by lazy {
        com.arkiv.player.pocketbase.MagisLinkClient(
            baseUrl = { settings.gatewayUrl.value },
            client = httpGateway,
            personToken = { sesionDePersona.token() },
            deviceToken = { deviceAuth.session.value?.token },
        )
    }

    init {
        applicationScope.launch {
            deviceAuth.ensureBootstrapped()
        }
        // CastPlayer/CastContext exigen el hilo principal. Forzamos su construcción ahí para que el
        // manager exista desde el arranque (y adopte una sesión ya viva) sin depender de quién lo
        // toque primero.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            castSession
        }
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
