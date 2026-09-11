package com.arkiv.player

import android.content.Context
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.catalog.AniListApi
import com.arkiv.player.data.catalog.AnimeMappingRepository
import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.SearchHistoryRepo
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.data.db.ArkivDatabase
import com.arkiv.player.data.recomendaciones.ArbitroDeIa
import com.arkiv.player.data.recomendaciones.BuscadorEnFuentes
import com.arkiv.player.data.recomendaciones.BuscadorEnTmdb
import com.arkiv.player.data.recomendaciones.GeneradorParaTi
import com.arkiv.player.data.recomendaciones.conKindReal
import com.arkiv.player.data.recomendaciones.NormalizarTitulo
import com.arkiv.player.data.recomendaciones.SenalesDeHistorial
import com.arkiv.player.data.recomendaciones.VerificacionParaTi
import com.arkiv.player.data.update.ApkDownloader
import com.arkiv.player.data.update.UpdateChecker
import com.arkiv.player.data.update.UpdateInfo
import com.arkiv.player.dlna.DlnaController
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
     * `OkHttpClient` del portal de Magis (Task 9, sub-proyecto 2B): con el subsistema de cuentas
     * afuera -`InterceptorDeSesion`, que colgaba acá para cerrar la sesión de la persona ante un
     * 401/403 real, se fue junto con el resto de `pocketbase/`- este cliente quedó con un solo
     * usuario, [magisPortal].
     *
     * `callTimeout` de 45 s: TOPE A LA LLAMADA ENTERA, no al socket -los timeouts sueltos de OkHttp
     * se reinician con cada byte que llega, así que una respuesta que llega a cuentagotas nunca
     * vencería-. [magisPortal] arma sobre este mismo cliente (mismo pool de conexiones) un
     * `readTimeout` más paciente, 25 s, porque el portal tarda hasta ~11 s en resolver algunos
     * canales (medido) y el default de lectura de OkHttp son 10 -lo mataba justo antes de llegar-.
     */
    val httpDelPortal: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
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
            // PACIENTE: el portal tarda ~11 s en resolver algunos canales (medido) y el default de
            // lectura de OkHttp son 10, o sea que los mataba justo antes de llegar. `newBuilder()`
            // y no un cliente nuevo: comparte pool de conexiones con el resto de las llamadas al
            // portal.
            http = httpDelPortal.newBuilder()
                .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
        )
    }

    internal val magisSession: com.arkiv.player.data.magis.MagisSession by lazy {
        com.arkiv.player.data.magis.MagisSession(magisPortal, magisStore)
    }

    /**
     * El vínculo con Magis visto desde "Ajustes → Cuenta" (celu y TV) y la oferta al entrar a la TV
     * (Task 8, sub-proyecto 2B): las tres pantallas dejaron de usar `AccountManager` para esto -ya
     * no depende de ninguna sesión de Kino, ver el KDoc de [com.arkiv.player.data.magis.CuentaDeMagis]-.
     * `AccountManager` mismo se borró del todo en la Task 9 (sub-proyecto 2B), junto con el resto
     * del subsistema de cuentas.
     */
    internal val cuentaDeMagis: com.arkiv.player.data.magis.CuentaDeMagis by lazy {
        com.arkiv.player.data.magis.CuentaDeMagis(magisSession)
    }

    private val magisCatalog: com.arkiv.player.data.magis.MagisCatalog by lazy {
        com.arkiv.player.data.magis.MagisCatalog(magisPortal, magisSession)
    }

    /** Los títulos de Magis, directo del portal. Afuera solo se ve a través de [fuenteDeContenido]. */
    private val magisFuente: com.arkiv.player.data.gateway.FuenteDeContenido by lazy {
        com.arkiv.player.data.magis.MagisFuente(
            catalogo = magisCatalog,
            resolucion = com.arkiv.player.data.magis.MagisResolve(magisPortal, magisSession),
            tmdb = tmdbApi,
        )
    }

    // --- Caracol (Ditu) directo ---------------------------------------------------------------
    //
    // Todo el protocolo de Caracol vive en `data/ditu`. Sin cuenta ni sesión: el contenido gratuito
    // se pide y se sirve (ver el KDoc de `DituCliente`).

    private val dituCliente: com.arkiv.player.data.ditu.DituClienteLike by lazy {
        com.arkiv.player.data.ditu.DituCliente()
    }

    /** Caracol como fuente de títulos. `internal` además de estar dentro de [fuenteDeContenido]:
     *  los canales y el catálogo completo no son parte del contrato común. */
    internal val dituFuente: com.arkiv.player.data.ditu.DituFuente by lazy {
        com.arkiv.player.data.ditu.DituFuente(
            catalogo = com.arkiv.player.data.ditu.DituCatalogo(dituCliente),
            episodios = com.arkiv.player.data.ditu.DituEpisodios(dituCliente),
            resolucion = com.arkiv.player.data.ditu.DituResolve(dituCliente),
            tmdb = tmdbApi,
        )
    }

    /**
     * De dónde salen los títulos que la app busca y reproduce: Magis y Caracol detrás de un solo
     * objeto. Para resolver y listar capítulos reparte por el `ref` (cada fuente reconoce los
     * suyos); para buscar, mezcla las dos. Ver [com.arkiv.player.data.gateway.FuenteCompuesta].
     */
    val fuenteDeContenido: com.arkiv.player.data.gateway.FuenteDeContenido by lazy {
        com.arkiv.player.data.gateway.FuenteCompuesta(listOf(magisFuente, dituFuente))
    }

    internal val magisLive: com.arkiv.player.data.magis.MagisLive by lazy {
        com.arkiv.player.data.magis.MagisLive(magisPortal, magisSession)
    }

    /** Categorías y canales de vivo + el árbol de secciones del catálogo, directo del portal. */
    internal val catalogoDeVivo: com.arkiv.player.data.magis.MagisLiveCatalog by lazy {
        com.arkiv.player.data.magis.MagisLiveCatalog(magisCatalog, magisPortal, magisSession)
    }

    /**
     * Proxy HLS local del canal en vivo. La firma de cada segmento se calcula EN EL APARATO y no
     * tiene respaldo: el respaldo era pedírsela al gateway, que en esta rama no existe. Si algún día
     * Magis cambia el algoritmo, se arregla publicando un APK (antes se arreglaba redesplegando el
     * servidor, que es justo la dependencia que esta rama saca).
     */
    val liveHlsProxy: com.arkiv.player.playback.LiveHlsProxy by lazy {
        com.arkiv.player.playback.LiveHlsProxy(
            com.arkiv.player.playback.FirmaLocal(),
            // Tras un doble 403 irrecuperable (sesión caducada, no firma): invalida la sesión
            // cacheada de ESE canal para que el próximo abrir()/precalentar() vuelva a resolver
            // contra el gateway en vez de reusar la que ya sabemos muerta hasta 300s más.
            onSesionMuerta = { canal -> liveController.invalidar(canal) },
        )
    }

    /** Abre canales en vivo: resuelve contra el portal y le entrega al reproductor la URL de
     *  [liveHlsProxy]. */
    val liveController: com.arkiv.player.ui.live.LiveController by lazy {
        com.arkiv.player.ui.live.LiveController(
            resolver = { code -> magisLive.resolverOLanzar(code) },
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
     * biblioteca) -antes también a `LibraryWiper` (logout), borrado en la Task 9 junto con el resto
     * de las cuentas- — mismo [almacenDeFrames], mismo `episodeFrameDao` que [frameCapturer].
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
     * Ni para "ditu": Caracol volvió con un cliente directo (`data/ditu`), pero su video viene
     * cifrado con Widevine y no hay forma de bajarlo; `DituDownloadStrategy` se borró en la poda y no
     * volvió. Una fila con `source="ditu"` cae al mismo camino de gracia.
     *
     * Las pantallas no ofrecen bajar lo que no tiene entrada acá: lo deciden
     * `FuenteDeDescarga.sePuedeBajar`/`hayEstrategia` con las claves de este mapa.
     */
    val downloadStrategies: Map<String, com.arkiv.player.data.local.DownloadStrategy> by lazy {
        mapOf(
            "magis" to com.arkiv.player.data.local.MagisDownloadStrategy(
                repository, fuenteDeContenido, httpRangeDownloader,
            ),
        )
    }

    val dlna: DlnaController by lazy { DlnaController(appContext) }

    val repository: ArkivRepository by lazy {
        ArkivRepository(
            database, tmdbApi,
            almacenDeFrames = almacenDeFrames,
            destructorDeFrames = destructorDeFrames,
        ).also { repo ->
            // "Para ti" solo existe en el home del TV: en el celular no hay fila que llenar, y cada
            // generación le pregunta a Kilo varias veces.
            if (DeviceType.isTelevision(appContext)) {
                repo.alTerminarAlgo = { applicationScope.launch { generadorParaTi.generarSiToca() } }
            }
        }
    }
    val aniListApi: AniListApi by lazy { AniListApi() }
    val animeMappingRepository: AnimeMappingRepository by lazy {
        AnimeMappingRepository(cacheDir = appContext.filesDir)
    }
    /**
     * Task 9 (sub-proyecto 2B): antes tomaba `httpGatewayCorto`, un cliente derivado de
     * `httpGateway.newBuilder()` solo para compartir su pool de conexiones -y que por eso heredaba
     * su `callTimeout(45 s)`-. Sin ese cliente compartido (ver [httpDelPortal], que ya es
     * únicamente del portal de Magis), `TmdbApi` vuelve a su propio `OkHttpClient` por default,
     * que ahora también lleva ese mismo `callTimeout(45 s)` -ver el default de su constructor- para
     * no perderlo: TMDB es OTRO host, así que compartir pool con el portal no traía ningún
     * beneficio real, pero el tope a la llamada entera sí hacía falta.
     */
    val tmdbApi: TmdbApi by lazy {
        TmdbApi(language = "es-MX")
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

    /** El cliente de los modelos gratis de Kilo (sub-proyecto 4). Sin llave: ver su KDoc. */
    internal val clienteDeIa: com.arkiv.player.data.ia.ClienteDeIa by lazy {
        com.arkiv.player.data.ia.ClienteDeIa(
            memoria = com.arkiv.player.data.ia.MemoriaDeModelos(
                com.arkiv.player.data.ia.AlmacenEnPreferencias(appContext),
            ) { System.currentTimeMillis() },
        )
    }

    /** El dato curioso del reproductor (sub-proyecto 4): Kilo, desde el aparato, un mes de caché. */
    internal val datosCuriosos: com.arkiv.player.data.trivia.DatosCuriosos by lazy {
        com.arkiv.player.data.trivia.DatosCuriosos(
            ia = { clienteDeIa.preguntar(it) },
            cache = com.arkiv.player.data.trivia.CacheDeDatosEnDisco(
                java.io.File(appContext.filesDir, "datos-curiosos"),
            ) { System.currentTimeMillis() },
        )
    }

    /**
     * "Para ti", generado en el aparato con Kilo (sub-proyecto 4). Verifica contra TMDB y contra la
     * fuente compuesta (Magis y Caracol). Cada paso con red atrapa sus fallos para que un candidato
     * roto no tumbe a los otros; la cancelación siempre se relanza.
     */
    internal val generadorParaTi: GeneradorParaTi by lazy {
        val verificacion = VerificacionParaTi(
            tmdb = BuscadorEnTmdb { tipo, titulo ->
                try {
                    tmdbApi.search(tipo, titulo).firstOrNull()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            },
            fuentes = BuscadorEnFuentes { titulo, tipo, _, tmdbId ->
                try {
                    fuenteDeContenido
                        .search(com.arkiv.player.data.gateway.GatewaySearchQuery(q = titulo, type = tipo, tmdbId = tmdbId))
                        .filterIsInstance<com.arkiv.player.data.gateway.SearchEvent.ResultEvent>()
                        // El `kind` que arma la fuente es el tipo que se BUSCÓ, no el del ítem (ver
                        // KDoc de `conKindReal`): se corrige acá, antes de que el árbitro vea la lista.
                        .map { conKindReal(it.item) }
                        .take(25)
                        .toList()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            },
            arbitro = ArbitroDeIa { clienteDeIa.preguntar(it) },
        )
        GeneradorParaTi(
            ia = { clienteDeIa.preguntar(it) },
            historial = { SenalesDeHistorial.de(database.playbackDao().historialReciente(100)) },
            yaVistos = {
                database.itemDao().getAllItems().filter { !it.deleted }.flatMap { item ->
                    listOfNotNull(
                        item.tmdbId?.takeIf { it > 0 }?.let { "tmdb:$it" },
                        NormalizarTitulo.de(item.title).takeIf { it.isNotEmpty() },
                        item.tituloCanonico?.let { NormalizarTitulo.de(it) }?.takeIf { it.isNotEmpty() },
                    )
                }.toSet()
            },
            verificar = { candidatos, vistos -> verificacion.verificar(candidatos, vistos) },
            guardar = { database.recomendacionDao().reemplazar(it, System.currentTimeMillis()) },
            leerMarcas = { settings.paraTiUltimoIntentoMs to settings.paraTiUltimoFueFalloDelModelo },
            escribirMarcas = { t, f -> settings.marcarIntentoDeParaTi(t, f) },
        )
    }

    /**
     * Agrega a la biblioteca lo que se elige en la fila "Para ti" del inicio. Necesita
     * `fuenteDeContenido` además del repositorio: una recomendación de serie trae el ref de la
     * temporada, y los capítulos hay que pedírselos al portal (`MagisCatalog.detail`).
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

    init {
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
