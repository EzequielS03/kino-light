package com.arkiv.player.ui.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.ArchiveUrls
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.CoincidenciaDeArchivo
import com.arkiv.player.data.EpisodeTorrent
import com.arkiv.player.data.GatewayConfigSource
import com.arkiv.player.data.Quality
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.data.db.LiveRecentDao
import com.arkiv.player.data.db.LiveRecentEntity
import com.arkiv.player.data.db.SkipMarkerEntity
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.model.Episode
import com.arkiv.player.data.model.VideoVariant
import com.arkiv.player.data.offline.ArkivOfflineApi
import com.arkiv.player.data.offline.PlaybackChoice
import com.arkiv.player.data.offline.PlaybackDecision
import com.arkiv.player.data.offline.PlaybackPreferenceStore
import com.arkiv.player.playback.ArchiveCacheProxy
import com.arkiv.player.playback.ContenidoDeAdultos
import com.arkiv.player.playback.MagisEfimero
import com.arkiv.player.playback.PlayerSource
import com.arkiv.player.playback.PoliticaOrigen
import com.arkiv.player.playback.SourceKind
import com.arkiv.player.playback.VentanaDeDescarga
import com.arkiv.player.playback.VentanaDeArchivo
import com.arkiv.player.torrent.EpisodeHint
import com.arkiv.player.torrent.TorrentEngine
import com.arkiv.player.torrent.TorrentProgress
import com.arkiv.player.ui.live.LiveController
import com.arkiv.player.ui.live.LiveZapping
import com.arkiv.player.ui.live.LiveZappingSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    val knownDurationMs: Long = 0L, // duración sondeada aparte, para fuentes cuya duración VLC no deduce (TS/HTTP)
    val preferirSoftware: Boolean = false, // HEVC de magis: el hardware falla y deja sin pistas. Ver PlayerSourceTag.
    /** Contenedor que declara la fuente ("ts", "mp4"…); "" = no se sabe. Ver PlayerSourceTag. */
    val contenedorDeLaFuente: String = "",
    /**
     * Si esto vino de una sección de adultos: nada de lo que suene con esta marca se anota en el
     * historial. Ver [com.arkiv.player.playback.ContenidoDeAdultos] y [hayQueAnotarHistorial].
     *
     * Viaja en el ÍTEM y no se consulta al vuelo por dos motivos. Uno, el ítem es lo único que
     * llega hasta acá: `saveProgress` recibe un `episodeId` pelado y no tiene de dónde deducir de
     * qué sección salió. Y dos, el contenido de adultos NO tiene fila en la biblioteca —esa es toda
     * la idea—, así que no hay a quién preguntarle después.
     *
     * `false` por default a propósito: es lo correcto para todas las fuentes que no son el catálogo
     * de Magis (archive, torrent, web, local), donde no existe la noción.
     */
    val adulto: Boolean = false,
)

/**
 * ¿Hay que anotar el progreso de [episodeId] en el historial?
 *
 * La pregunta se contesta contra la playlist que está sonando porque `saveProgress` recibe un
 * `episodeId` pelado, no un ítem. Vive acá afuera —y no dentro del ViewModel— por lo mismo que
 * [com.arkiv.player.playback.ContenidoDeAdultos] vive afuera de `savePlayback`: es donde se pueden
 * fijar sus bordes con tests.
 *
 * El borde que importa es el episodio que NO está en la playlist, y se resuelve ANOTANDO. No es un
 * caso teórico: el ViewModel sobrevive a la navegación entre capítulos y `_playlist` sigue
 * publicando la del capítulo anterior mientras la fuente nueva resuelve (ver [PlaylistData.pedido]),
 * así que hay ventanas de segundos donde el episodio preguntado todavía no está. Leer eso como "es
 * adulto" dejaría de guardar el progreso de contenido normal en silencio.
 */
internal fun PlaylistData?.hayQueAnotarHistorial(episodeId: String): Boolean =
    ContenidoDeAdultos.hayQueAnotar(this?.items?.firstOrNull { it.episodeId == episodeId }?.adulto)

/** La sección como playlist: todos los episodios + dónde/cómo arrancar. */
data class PlaylistData(
    val items: List<PlayerData>,
    val startIndex: Int,
    val startPositionMs: Long,
    /**
     * El episodeId que se pidió cargar cuando se armó esta playlist, o sea DE QUÉ CAPÍTULO es.
     *
     * Existe porque el ViewModel sobrevive a la navegación entre capítulos y este StateFlow sigue
     * publicando la playlist del capítulo anterior hasta que la fuente nueva termina de resolver
     * (segundos, en magis/web). Sin esta marca, la pantalla no tenía forma de distinguir "ya llegó
     * lo mío" de "esto todavía es lo de antes", y cargaba lo viejo: elegir el capítulo siguiente en
     * el carrusel volvía a reproducir el que estaba sonando. Ver [MediaReusePolicy.decide].
     *
     * NO es "el capítulo que suena ahora": archive carga la sección entera y el player avanza solo
     * dentro de ella sin volver a pedir nada (eso lo responde `episodioEnCurso` en PlayerScreen).
     */
    val pedido: String,
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

/**
 * Mensaje de error para un canal en vivo que no abrió. Función pura (nada de red/estado) para
 * poder testearla sin construir todo [PlayerViewModel] -- tiene ~15 dependencias, la mayoría de
 * red/disco.
 *
 * Antes de esto, un TV sin la config del gateway (el fallo de diseño real: nunca la tuvo) mostraba
 * el mismo "No se pudo abrir X" que un canal caído de verdad -- el usuario no tenía forma de
 * distinguir "el portal está mal" de "este TV no está vinculado", así que no sabía qué hacer.
 * [esTelevision] + [fuenteGateway] alcanza para distinguir el caso sin tocar la excepción real
 * (evita parsear mensajes/códigos HTTP, que es frágil): si es un TV que TODAVÍA está en el default
 * baked-in -nunca sincronizó por pareo ni se le fijó una config a mano-, cualquier fallo al abrir
 * un canal probablemente es por eso.
 */
fun mensajeErrorVivo(
    esTelevision: Boolean,
    fuenteGateway: GatewayConfigSource,
    nombreCanal: String,
): String =
    if (esTelevision && fuenteGateway == GatewayConfigSource.DEFAULT) {
        "Este TV no tiene la configuración del servicio en vivo. Volvé a vincularlo: en el " +
            "teléfono abrí Kino, Conexión con el TV, Re-parear, y escaneá el código acá."
    } else {
        "No se pudo abrir $nombreCanal"
    }

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
    private val deviceAuth: com.arkiv.player.pocketbase.DeviceAuthManager,
    private val frameCapturer: com.arkiv.player.miniaturas.FrameCapturer,
    // Tarea 14 (modo vivo): pegados al final para no reordenar los parámetros posicionales de
    // arriba (el callsite en PlayerScreen los pasa por posición, no por nombre).
    private val liveController: LiveController,
    private val liveRecentDao: LiveRecentDao,
    // ¿Este proceso corre en un Android TV? Solo importa para [mensajeErrorVivo]: ahí (y no en el
    // celu) un 401/lo-que-sea al abrir un canal suele ser el TV sin vincular, no el portal caído.
    private val esTelevision: Boolean = false,
    // Task 7b: el `OkHttpClient` COMPARTIDO de `AppGraph` con `InterceptorDeSesion` colgado. Antes
    // [gatewayClient] armaba su PROPIO `OkHttpClient()` (uno de los seis sueltos del brief), así
    // que un 401/403 de identidad real disparado por la precarga en frío del siguiente capítulo
    // -sin que ninguna pantalla esté mirando- no cerraba la sesión hasta el próximo pedido que sí
    // pasara por un ViewModel que supiera reaccionar.
    private val httpGateway: okhttp3.OkHttpClient,
    // Task 8: [gatewayClient] es OTRA instancia de `ArkivApiClient` además de
    // `AppGraph.arkivApiClient` -esta la usa [prefetchNext] para pre-resolver el próximo capítulo
    // de Magis-, así que también necesita las dos cabeceras de sesión: desde el Paso 3
    // `X-Arkiv-Key` ya no existe, así que sin esto `/v1/resolve` se hubiera quedado sin NINGUNA
    // credencial. `deviceAuth` ya viene por constructor arriba -de ahí sale el token del
    // aparato-; el de la persona no tenía por dónde
    // entrar, así que se suma esta lambda en vez de todo `SesionDePersona` (acá alcanza con leer
    // el token, igual que ya hace [deviceAuth] para el suyo).
    private val personToken: () -> String? = { null },
) : ViewModel() {

    private val _playlist = MutableStateFlow<PlaylistData?>(null)
    val playlist: StateFlow<PlaylistData?> = _playlist.asStateFlow()

    /** Error de resolución (torrent sin peers, .torrent ilegible, etc.) para que la pantalla lo muestre. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Si lo que hay en [_error] vino de un TROPIEZO del player y no de no poder abrir la fuente.
     *
     * Son dos situaciones distintas que hasta ahora compartían canal. "No hay peers" o "no se pudo
     * resolver la fuente" significan que NO hay nada sonando: el cartel tiene que quedarse. En
     * cambio [onPlaybackFailed] se dispara con un PlaybackException, y de esos hay que se reparan
     * solos —un tirón de red, un rebuffer que VLC remonta— con el video siguiendo de largo. Ahí el
     * cartel queda mintiendo sobre un video que anda bien, y encima tapa los controles: la barra se
     * compone con `loadError == null`, así que mientras esté en pantalla el D-pad no llega al
     * slider y no se puede ni pausar. Visto en el Fire TV el 2026-08-12.
     *
     * Ver [onReproduccionViva], que es quien lo apaga.
     */
    private var errorDeReproduccion = false

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
        // Modo vivo (Tarea 14): CORTA ACÁ, antes de tocar nada del camino VOD de abajo -- ni
        // marcarEnCurso, ni localLibrary, ni el prefetch del final (repo.nextEpisode() no sabe de
        // canales). Es la bandera que aísla TODO el comportamiento distinto: un canal en vivo no
        // tiene duración que sondear (ver KDoc de LiveZapping/LiveController -- sondearla es lo
        // que rompía el VOD de Magis), progreso que guardar, ni "siguiente capítulo" de series --
        // el único "siguiente" que existe en vivo es el zapping.
        if (PlayerSource.kindFor(episodeId) == SourceKind.LIVE) {
            loadLive(episodeId.removePrefix(PlayerSource.LIVE_PREFIX))
            return
        }
        viewModelScope.launch {
            // Antes que nada: que el detalle sepa por qué capítulo vas aunque salgas enseguida.
            //
            // Salvo que no haya que anotarlo. Este es el TERCER camino de escritura del historial,
            // y el que se escapó de los otros dos: no escribe posición ni duración —la fila queda
            // en 0— pero SÍ escribe `lastPlayedAt`, y `playback` se sincroniza. O sea deja el
            // registro con hora de que esto se vio, y lo manda a la nube y a los otros aparatos.
            // Encontrado reproduciendo de verdad en el Fire TV el 2026-08-14: los guardas de
            // progreso, frames y biblioteca aguantaron los tres, y esta fila apareció igual.
            //
            // Acá NO sirve [hayQueAnotarHistorial]: esto corre ANTES de resolver la fuente, cuando
            // `_playlist` todavía es la del episodio anterior (o null), así que preguntarle daría
            // "no sé" → anotar, que es justo lo contrario de lo que hace falta. Lo que sí se sabe a
            // esta altura es el pendiente efímero, que la pantalla dejó antes de navegar.
            if (ContenidoDeAdultos.hayQueAnotar(MagisEfimero.tomar(episodeId)?.adulto)) {
                runCatching { repo.marcarEnCurso(episodeId) }
            }
            _error.value = null
            errorDeReproduccion = false
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
                SourceKind.MAGIS -> loadMagis(episodeId)
                // PlayerSource.kindFor() nunca devuelve NUC ni LOCAL (ver su propio KDoc): esta rama
                // es inalcanzable por diseño, pero el `when` exhaustivo la exige. Apunta a loadWeb()
                // -no a la loadWebRespectingPreference() desconectada- para que la afirmación del
                // KDoc de esa función ("load() llama a loadWeb directo") sea cierta para TODAS las
                // ramas, no solo la de WEB.
                SourceKind.NUC, SourceKind.LOCAL -> loadWeb(episodeId)
                // Inalcanzable: se corta arriba del todo, antes de este launch (ver el guard de
                // más arriba). La rama existe porque el `when` sobre SourceKind es exhaustivo.
                SourceKind.LIVE -> Unit
            }
        }
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.IO) { prefetchNext(episodeId) }
    }

    // --- Modo vivo (Tarea 14) ---------------------------------------------------------------
    // Aislado del resto del archivo a propósito (ver el guard al principio de load()): nada de
    // esto participa en playlists de VOD, casteo, torrent o resume -- son conceptos que en vivo
    // no existen. Ver KDoc de LiveController/LiveZapping para el porqué completo.

    /** Zapping en curso -- null fuera de modo vivo. */
    private var zapping: LiveZapping? = null

    /** Job cancelable del precalentado de vecinos -- ver KDoc de [precalentarVecinos]. */
    private var precalentarJob: kotlinx.coroutines.Job? = null

    private val _liveCanal = MutableStateFlow<LiveChannel?>(null)

    /** El canal en pantalla ahora mismo (código/nombre/número/logo), para el overlay de PlayerScreen. */
    val liveCanal: StateFlow<LiveChannel?> = _liveCanal.asStateFlow()

    private val _generacionVivo = MutableStateFlow(0)

    /**
     * Sube en CADA carga de un canal en vivo: abrir, zapear y reabrir tras un corte.
     *
     * Existe porque [playlist] no alcanza para avisar de una reapertura: el `PlaylistData` que se
     * publica al reabrir el mismo canal es igual al anterior y `StateFlow` no emite valores
     * iguales. La pantalla mira las dos cosas, así que una carga siempre le llega aunque el
     * contenido no haya cambiado ni un byte.
     */
    val generacionVivo: StateFlow<Int> = _generacionVivo.asStateFlow()

    /**
     * Arranca el zapping sobre la lista con la que el usuario ENTRÓ (ver [LiveZappingSource]), no
     * el catálogo completo -- es la que tiene en la cabeza. Sin nada fijado ahí (proceso recreado
     * a mitad del reproductor en vivo, o un llamador que no pasó por la grilla) cae a una lista de
     * un solo canal: se pierde el zapping, pero el canal elegido reproduce igual.
     */
    private fun loadLive(code: String) {
        val entrada = LiveZappingSource.lista.ifEmpty { listOf(LiveChannel(code, code, 0, null)) }
        val indice = entrada.indexOfFirst { it.code == code }.coerceAtLeast(0)
        zapping = LiveZapping(entrada, indice)
        abrirCanalActual()
    }

    /**
     * Abre el canal actual del zapping: resuelve contra [liveController] y publica un playlist de
     * UN solo ítem que arranca siempre en 0 -- en vivo no hay "dónde ibas" que reanudar. NO sondea
     * duración (no la hay) y NO guarda progreso (ver [saveProgress], que PlayerScreen ya no llama
     * en modo vivo). Anota el canal en [liveRecentDao] -- es lo único que llena el chip
     * "Recientes" de la grilla, que hasta esta tarea nadie escribía.
     */
    private fun abrirCanalActual() {
        val canal = zapping?.actual ?: return
        _liveCanal.value = canal
        viewModelScope.launch {
            _error.value = null
            errorDeReproduccion = false
            val url = runCatching { liveController.abrir(canal.code) }.getOrElse {
                Log.w(PLAY, "abrirCanalActual() falló para ${canal.code}: ${it.message}")
                if (zapping?.actual?.code == canal.code) {
                    _error.value = mensajeErrorVivo(esTelevision, settings.gatewayConfigSource.value, canal.nombre)
                }
                return@launch
            }
            // Zapeos rápidos: si para cuando este abrir() (~3s en el peor caso) vuelve el usuario
            // ya zapeó a OTRO canal, esta respuesta tardía no debe pisar lo que hay en pantalla --
            // mismo patrón (y mismo motivo) que LiveViewModel.cargar() con categoriaActiva, ver su
            // KDoc.
            if (zapping?.actual?.code != canal.code) return@launch
            val item = PlayerData(
                episodeId = "${PlayerSource.LIVE_PREFIX}${canal.code}",
                itemId = "${PlayerSource.LIVE_PREFIX}${canal.code}",
                title = canal.nombre,
                subtitle = "",
                mediaUrl = url,
                // Un canal en vivo nunca tiene un mp4 h.264 de respaldo -es un directo, no un
                // archivo-, así que castUrl siempre es null. Eso NO significa que no castee (Tarea
                // 18): PlayerScreen.castRequestFor resuelve la URL alcanzable por LAN del proxy
                // local (LiveHlsProxy.lanUrl) por su cuenta, igual que hace con torrent -- ver su
                // KDoc.
                castUrl = null,
                artworkUrl = canal.logo.orEmpty(),
                openingStartMs = null, openingEndMs = null, endingStartMs = null,
                kind = SourceKind.LIVE,
            )
            // `pedido` = el canal que se acaba de abrir, no el que se pidió al entrar: zapear cambia
            // el canal DENTRO de esta pantalla sin navegar (ver KDoc de loadLive), así que la
            // pantalla lo trata aparte -- en vivo nunca pasa por MediaReusePolicy.
            _playlist.value = PlaylistData(listOf(item), 0, 0L, pedido = item.episodeId)
            // Y el aviso de que ACÁ HUBO UNA CARGA, aunque el valor de arriba sea idéntico al que
            // ya estaba. Reabrir un canal cortado produce un `PlaylistData` **igual** al anterior
            // -mismo canal, y `mediaUrl` es la url del proxy local, cuyo puerto y token viven
            // tanto como el socket-, y un `StateFlow` descarta los valores iguales: la pantalla no
            // se enteraba, no volvía a llamar a `setMediaItems`, y la reapertura quedaba en el log
            // sin que se reprodujera nada. Medido en el Fire TV el 2026-08-14: `canal →` a las
            // 22:19:20 y después silencio, con la sesión de medios congelada en pos=99631ms.
            _generacionVivo.value++
            // Un canal de adultos NO se anota. Y se resuelve NO ESCRIBIENDO en vez de filtrando
            // al leer: lo que no se escribe no se puede escapar por una pantalla que nos
            // olvidamos —"Recientes" se pinta en la guía, en el cajón y en el celular— y además
            // nunca se sube a la nube, así que tampoco aparece en los otros aparatos de la
            // cuenta. Filtrar al leer deja el dato adentro esperando el primer lugar que no
            // filtre.
            // Por [ContenidoDeAdultos] y no por un `!canal.adulto` suelto: la regla es la misma que
            // la del progreso y la de los frames, y tenerla escrita en un solo lugar es lo que
            // evita que mañana una de las tres se corrija y las otras dos no.
            if (ContenidoDeAdultos.hayQueAnotar(canal.adulto)) {
                runCatching {
                    liveRecentDao.anotar(LiveRecentEntity(canal.code, canal.nombre, System.currentTimeMillis()))
                }
            }
            precalentarVecinos()
        }
    }

    /** Zapping: siguiente/anterior de la lista con la que se entró. Sin efecto fuera de modo vivo. */
    fun zapSiguiente() { zapping?.siguiente() ?: return; abrirCanalActual() }
    fun zapAnterior() { zapping?.anterior() ?: return; abrirCanalActual() }

    /** Reaperturas seguidas del canal actual sin que haya vuelto a dar imagen, y de qué canal son. */
    private var reaperturasVivo = 0
    private var canalDelContador: String? = null
    private var reabrirJob: kotlinx.coroutines.Job? = null

    /**
     * Cuándo empezó el hueco sin imagen que estamos tratando de tapar (0 = no hay ninguno).
     *
     * Es el número que mide lo que la persona VE. `pos` y los códigos del CDN cuentan qué pasó por
     * dentro; esto cuenta cuántos segundos estuvo la pantalla sin avanzar, que es lo único por lo
     * que se juzga si el vivo quedó usable.
     */
    private var cortadoEn = 0L

    /**
     * El directo se cortó: reabrirlo, porque un directo no termina.
     *
     * Un `EndReached` en vivo nunca es "se acabó el contenido" — es que el reproductor se quedó sin
     * datos. Hasta ahora eso dejaba el canal muerto y ahí se quedaba: la pantalla se congelaba y la
     * única salida era volver atrás y entrar de nuevo. Medido en el Fire TV el 2026-08-14, cuatro
     * veces seguidas con RCN FHD: el origen de esa señal fallaba de a ratos —404 en los segmentos y
     * hasta en el playlist— y a los pocos segundos volvía solo. O sea que lo que faltaba no era
     * adivinar mejor el fallo, era volver a intentar.
     *
     * Tres reaperturas con espera que se duplica (2 s, 4 s, 8 s): cubre un bache de ~15 s, que es de
     * la magnitud de lo medido. Al cuarto corte se avisa en pantalla en vez de seguir. Reintentar sin
     * tope dejaría un canal dado de baja en bucle para siempre, gastando datos y sin decir nunca qué
     * está pasando — el silencio es peor que el error.
     *
     * El presupuesto es POR CANAL ([canalDelContador]) y se repone entero apenas el canal vuelve a
     * reproducir ([vivoAndando]): si aguanta una hora y después tiene un hipo, arranca de cero.
     */
    fun reabrirVivoPorCorte() {
        val canal = zapping?.actual ?: return
        if (canal.code != canalDelContador) {
            canalDelContador = canal.code
            reaperturasVivo = 0
        }
        if (reaperturasVivo >= MAX_REAPERTURAS_VIVO) {
            Log.w(PLAY, "vivo: ${canal.code} no volvió tras $MAX_REAPERTURAS_VIVO reaperturas → aviso")
            _error.value = "Se cortó la señal de ${canal.nombre} y no volvió. " +
                "Puede ser un problema del canal: probá de nuevo o mirá otro."
            return
        }
        if (cortadoEn == 0L) cortadoEn = System.currentTimeMillis()
        reaperturasVivo++
        val espera = ESPERA_REAPERTURA_MS shl (reaperturasVivo - 1)
        Log.w(
            PLAY,
            "vivo: ${canal.code} se cortó → reabro en ${espera}ms " +
                "(intento $reaperturasVivo/$MAX_REAPERTURAS_VIVO)",
        )
        reabrirJob?.cancel()
        reabrirJob = viewModelScope.launch {
            delay(espera)
            // Zapear durante la espera gana: reabrir acá el canal viejo pisaría el que la persona
            // acaba de elegir.
            if (zapping?.actual?.code == canal.code) abrirCanalActual()
        }
    }

    /**
     * El canal se está reproduciendo de verdad: se le repone el presupuesto de reaperturas.
     *
     * Pide la POSICIÓN y no un booleano porque `isPlaying` se pone en true apenas VLC abre el
     * medio, antes del primer fotograma: con eso, un canal que reabría y moría en `pos=0ms`
     * reponía igual el presupuesto, el tope no se agotaba nunca y el aviso de [reabrirVivoPorCorte]
     * era inalcanzable. [MINIMO_VIVO_SANO_MS] es la línea entre "se recuperó" y "reabrió y se cayó
     * de nuevo".
     */
    fun vivoAndando(posicionMs: Long) {
        if (reaperturasVivo == 0 || posicionMs < MINIMO_VIVO_SANO_MS) return
        val hueco = if (cortadoEn > 0L) System.currentTimeMillis() - cortadoEn else -1L
        Log.w(
            PLAY,
            "vivo: recuperado tras ${hueco}ms sin imagen y $reaperturasVivo reapertura(s) " +
                "(reprodujo ${posicionMs}ms) → repongo el presupuesto",
        )
        reaperturasVivo = 0
        cortadoEn = 0L
    }

    /**
     * El cajón de canales eligió otro canal: cambia el canal Y la lista que el zapping recorre.
     *
     * Las dos cosas juntas a propósito. El cajón lista el catálogo entero por categorías, así que
     * el canal elegido puede no estar en la lista con la que se entró — dejar el zapping viejo
     * haría que la primera flecha arriba saltara a un canal de otra categoría, sin relación con
     * lo que se acaba de elegir. `lista` es la que el cajón tenía en pantalla (ya filtrada por la
     * búsqueda, si había una), que es exactamente lo que se espera recorrer después.
     *
     * También se fija en [LiveZappingSource] para que sobreviva a una recreación de la pantalla,
     * que es de donde [loadLive] la lee.
     */
    fun irACanal(lista: List<LiveChannel>, canal: LiveChannel) {
        val entrada = lista.ifEmpty { listOf(canal) }
        LiveZappingSource.lista = entrada
        zapping = LiveZapping(entrada, entrada.indexOfFirst { it.code == canal.code }.coerceAtLeast(0))
        abrirCanalActual()
    }

    /**
     * Precalienta los vecinos del zapping ~1s después de abrir el canal actual -- si el usuario
     * zapea antes de que pase ese segundo, [abrirCanalActual] cancela este job (siguiente llamada)
     * antes de programar el próximo. Resolver cuesta ~3s (dos llamadas a un portal cortado a 1
     * cada 1,5s, ver KDoc de LiveController), así que vale la pena adelantarlo mientras el usuario
     * no está zapeando activamente. Best-effort: un vecino que falla no impide que el otro se
     * intente, y ninguno de los dos bloquea nada -- el playlist ya se publicó antes de llegar acá.
     */
    private fun precalentarVecinos() {
        precalentarJob?.cancel()
        val vecinos = zapping?.vecinos() ?: return
        precalentarJob = viewModelScope.launch {
            delay(1000)
            vecinos.forEach { vecino -> launch { runCatching { liveController.precalentar(vecino.code) } } }
        }
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
        _playlist.value = PlaylistData(listOf(item), 0, startPos, pedido = episodeId)
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
        // La posición se calcula ANTES de armar los ítems: el que se va a reanudar necesita que su
        // URL lleve el punto de arranque, para que el proxy baje desde ahí en vez de desde el
        // principio (ver VentanaDeDescarga). Los demás van como siempre.
        val startPos = safeStartPosition(episodeId, SourceKind.ARCHIVE)
        val items = episodes.mapNotNull { ep ->
            buildData(ep, localArchiveUri(ep.id), marker, if (ep.id == episodeId) startPos else 0L)
        }
        if (items.isEmpty()) return
        val startIndex = items.indexOfFirst { it.episodeId == episodeId }.coerceAtLeast(0)
        _playlist.value = PlaylistData(items, startIndex, startPos, pedido = episodeId)
    }

    /**
     * Episodios a los que ya se les intentó el sanado. Sin esto, un ítem que quedó realmente roto
     * entra en bucle: falla → refresca → vuelve a fallar → refresca…
     */
    private val sanadoIntentado = mutableSetOf<String>()

    /**
     * El reproductor no pudo con este episodio. Acá se decide si es algo que la app puede arreglar
     * sola o algo que hay que contarle al usuario.
     *
     * Antes esto no existía: un fallo de reproducción no llegaba nunca a [_error] —que es lo único
     * que la pantalla pinta— así que la película simplemente no arrancaba y no aparecía ningún
     * mensaje. Medido el 2026-08-10: `EncounteredError` en el log y `error=false` en la UI.
     *
     * El caso que sí se repara es el 404. La app **cachea el nombre del archivo** en la base local
     * (ver [CoincidenciaDeArchivo]), así que si archive.org renombra o vuelve a derivar, el path
     * guardado apunta a la nada para siempre. Refrescar la metadata y volver a ubicar el capítulo
     * lo devuelve a la vida sin que nadie tenga que reimportar el ítem a mano.
     */
    fun onPlaybackFailed(episodeId: String) {
        viewModelScope.launch {
            // Todo lo que se escriba de acá para abajo describe un tropiezo del player, no una
            // fuente que no se pudo abrir: si el video remonta, deja de ser cierto. Ver
            // [errorDeReproduccion].
            errorDeReproduccion = true
            val episode = repo.getEpisode(episodeId)
            if (episode == null || PlayerSource.kindFor(episodeId) != SourceKind.ARCHIVE) {
                _error.value = "No se pudo reproducir este capítulo"
                return@launch
            }
            val origen = streamingVariant(episode)?.let { ArchiveUrls.download(episode.itemId, it.path) }
            when (val code = origen?.let { archiveCacheProxy.ultimoCodigoDe(it) }) {
                404 -> sanarRenombre(episode)
                // Distinguirlos vale la pena: son las dos formas en que archive.org falla y piden
                // cosas opuestas del usuario (esperar vs. buscar otra copia). Visto los dos el
                // mismo día: 503 en un ítem con la metadata corrupta, timeouts en uno sano pero
                // servido por un nodo que tardaba 72 s.
                503 -> _error.value = "archive.org no está sirviendo este ítem ahora (503). " +
                    "Suele ser del lado de ellos: probá más tarde o buscá otra copia."
                PoliticaOrigen.SIN_RESPUESTA, null -> _error.value =
                    "archive.org no respondió a tiempo. Probá de nuevo."
                else -> _error.value = "archive.org devolvió $code y no se pudo reproducir"
            }
        }
    }

    /**
     * El video está sonando: si lo que hay en pantalla era un tropiezo de reproducción, ya no
     * describe nada y se va.
     *
     * Lo llama el sondeo de la pantalla en cada tick mientras el player esté listo y reproduciendo,
     * y no el `onIsPlayingChanged` del listener, a propósito: hay tropiezos que VLC remonta sin que
     * `isPlaying` llegue a caer, así que colgado de esa transición el cartel se quedaba puesto
     * justamente en el caso más común. Es idempotente y sale por el `if` en cuanto no hay nada que
     * limpiar, que es siempre salvo el instante posterior a un fallo.
     *
     * Los errores de RESOLUCIÓN no se tocan: ahí no hay video sonando (o el que suena es el ítem
     * viejo, mientras el nuevo no pudo abrirse) y el cartel es la única señal de lo que pasó.
     */
    fun onReproduccionViva() {
        if (!errorDeReproduccion) return
        errorDeReproduccion = false
        _error.value = null
    }

    /**
     * Vuelve a pedir la metadata del ítem y busca dónde quedó el capítulo.
     *
     * Solo se sigue adelante si la coincidencia es inequívoca: [CoincidenciaDeArchivo] devuelve
     * null antes que arriesgarse, porque reproducir OTRO capítulo sin avisar es peor que el error.
     */
    private suspend fun sanarRenombre(episode: Episode) {
        if (!sanadoIntentado.add(episode.id)) {
            _error.value = "El archivo ya no está en archive.org y no se encontró su reemplazo"
            return
        }
        val pathViejo = streamingVariant(episode)?.path
        if (pathViejo == null) { _error.value = "No se pudo reproducir este capítulo"; return }
        Log.w("ArkivPlay", "404 en archive → refresco la metadata de ${episode.itemId}")
        val refresco = repo.refreshItem(episode.itemId)
        if (refresco.isFailure) {
            _error.value = "El archivo ya no está en archive.org y no se pudo refrescar el ítem"
            return
        }
        val nuevos = repo.episodesOf(episode.itemId)
        val elegido = CoincidenciaDeArchivo.mejor(
            pathViejo,
            nuevos.mapNotNull { streamingVariant(it)?.path },
        )
        val reemplazo = elegido?.let { path -> nuevos.firstOrNull { streamingVariant(it)?.path == path } }
        if (reemplazo == null) {
            Log.w("ArkivPlay", "sanado: no hay reemplazo claro para $pathViejo")
            _error.value = "archive.org ya no tiene este archivo. Se actualizó la biblioteca del ítem."
            return
        }
        Log.w("ArkivPlay", "sanado: $pathViejo → ${streamingVariant(reemplazo)?.path}")
        _error.value = null
        load(reemplazo.id)
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
        _playlist.value = PlaylistData(listOf(item), 0, startPos, pedido = episodeId)
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
        _playlist.value = PlaylistData(listOf(item), 0, startPos, pedido = episodeId)
    }

    /**
     * Fuente web: resuelve la pageUrl → stream directo vía el resolver headless de blog, y arma el
     * PlayerData con esa URL. El player unificado hereda controles/seek/cast/dlna/subs/audio. Los
     * subtítulos+headers sniffeados viajan por [webExtras] para que PlayerScreen los adjunte.
     */
    /**
     * Reproduce un ítem de Magis.
     *
     * El CDN exige `Content-Auth` y `Content-License`, y libVLC solo sabe mandar Referer y
     * User-Agent: por eso el stream va por el proxy local, que sí puede ponerlos en la petición al
     * origen. El [ref] guardado se manda tal cual a `/v1/resolve`; la app nunca lo interpreta.
     */
    private suspend fun loadMagis(episodeId: String) {
        // El contenido de adultos NO tiene fila en la biblioteca —esa es toda la idea, ver
        // [MagisEfimero]—, así que su `ref` no se puede leer de ahí: viaja por afuera.
        val efimero = MagisEfimero.tomar(episodeId)
        val ref = efimero?.ref ?: repo.magisRefForEpisode(episodeId)
        Log.w(PLAY, "loadMagis() episodeId=$episodeId efimero=${efimero != null} ref=${ref?.take(12)}…")
        if (ref.isNullOrBlank()) { _error.value = "No se encontró la fuente de Magis"; return }

        _playlist.value = null
        _webExtras.value = null
        _resolving.value = true
        // CRONÓMETRO DEL ARRANQUE. Cada fase se mide por separado y al final se emite un resumen en
        // UNA línea: el cuello de botella de magis se mudó tres veces mientras se optimizaba (VLC →
        // sonda+precalentado → gateway), y cada mudanza costó una ronda de "reproducí algo y miro
        // los logs" porque los tiempos había que deducirlos de los huecos entre líneas sueltas.
        val t0 = System.currentTimeMillis()
        val resuelto = withContext(Dispatchers.IO) { runCatching { gatewayClient.resolve(ref) } }
        val msResolve = System.currentTimeMillis() - t0
        _resolving.value = false
        Log.w(PLAY, "loadMagis() resolve del gateway=${msResolve}ms")

        val play = resuelto.getOrNull()
        if (play == null) {
            Log.w(PLAY, "loadMagis() falló: ${resuelto.exceptionOrNull()?.message}")
            _error.value = "No se pudo resolver esta fuente de Magis"
            return
        }

        // Los idiomas que declara el portal son lo ÚNICO que permite elegir subtítulo por idioma en
        // magis: sus pistas embebidas llegan sin idioma en ningún campo (medido en device,
        // `language=null` en `IMedia.Track` y nombre pelado "Track 1", mientras las de audio sí traen
        // spa/eng/jpn). Viajan por [webExtras] y los cruza VlcPlayer.clasificarSpuConFuente.
        Log.w(PLAY, "loadMagis() subtitulos del portal=${play.subtitles.size} langs=${play.subtitles.map { it.lang }}")

        withContext(Dispatchers.IO) { archiveCacheProxy.start() }
        // Sin fila en la biblioteca no hay cabecera que leer: el título lo trae el propio pendiente,
        // que es lo que la pantalla de categorías tenía en la mano al tocarlo.
        val cabecera = if (efimero != null) null else repo.headerInfo(episodeId)
        // `directo`: el proxy reenvía cada Range al CDN sin cachear. Con la caché (el camino de
        // archive) la descarga de ~1 GB se corta, el proxy borra el archivo y vuelve a empezar en 0
        // mientras VLC sigue leyendo por el offset viejo → el TS le llega con huecos, el tiempo salta
        // de a minutos y el video se muere. Sin caché no hay nada que truncar.
        val urlLocal = archiveCacheProxy.proxyUrl(play.url, play.headers, directo = true)
        // LA DURACIÓN YA NO SE BUSCA ANTES DE ARRANCAR. La calcula libVLC solo.
        //
        // Esto era el respaldo de cuando magis se demuxeaba con el `ts` nativo, que sobre HTTP no
        // deducía la duración y dejaba la barra llena y en 00:00. Desde que se demuxea con avformat
        // (ver la opción `:demux=avformat` en VlcPlayer) ese respaldo dejó de hacer falta: medido en
        // el Fire TV el 2026-08-13, VLC informó `dur=7010048ms` en una película y `dur=3831168ms` en
        // un capítulo de serie, ambos al primer latido y ambos coincidiendo con lo que devolvía la
        // sonda (7009961 y 3831000). `UnknownLengthPolicy.effectiveDurationMs` ya prefiere la de VLC
        // cuando existe, así que lo que salía de acá se descartaba un segundo después.
        //
        // Y no salía gratis: en ese mismo capítulo, conseguirla costó 9,2 s de spinner —dos viajes
        // al CDN antes de abrir el video, contra un origen que tarda entre 0,2 s y 20 s por rango—
        // para un número que llegaba solo. Si el gateway la manda (las películas la traen gratis en
        // el resolve) se aprovecha; si no, se arranca sin ella y VLC la completa.
        if (play.durationMs > 0) {
            Log.w(PLAY, "loadMagis() duracion del gateway=${play.durationMs}ms")
        }
        // El ARRANQUE CALIENTE: lo ÚNICO que se espera antes de abrir el video.
        //
        // Se precalienta en el byte 0, que es donde VLC abre SIEMPRE desde que magis dejó de abrir
        // por ventana: reanuda saltando por tiempo, no abriendo el stream más adelante. Sin él, si
        // la primera lectura se demora libVLC se rinde identificando el stream y se queda SIN PISTAS
        // para siempre (negro y mudo, con el reloj disparado).
        //
        // La COLA sigue bajándose por detrás —para los sondeos de EOF de libVLC, que quiere el final
        // del archivo apenas abre— pero ya nunca frena el arranque: `esperarCola=false` sin
        // condiciones. Ver ArchiveCacheProxy.precalentar y PrecalentadoNoBloqueaTest.
        val tArranque = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            runCatching {
                archiveCacheProxy.precalentar(
                    play.url, play.headers, fraccion = 0f, esperarCola = false,
                    // El contenedor decide si hace falta traer la cola del archivo: un mp4 abre sin
                    // leer el final y bajarla es gasto puro contra el CDN. Si el gateway no lo
                    // manda, la extensión de la URL lo dice igual para magis.
                    contenedor = play.container.ifBlank {
                        com.arkiv.player.playback.ContenedorDeVideo.extensionDeVideo(play.url).orEmpty()
                    },
                )
            }
        }
        val msArranque = System.currentTimeMillis() - tArranque
        // Si el gateway mandó la duración, se aprovecha; si no, se arranca sin ella y la completa
        // VLC al abrir. Nada de esto pide un solo byte extra.
        val duracion = play.durationMs
        Log.w(PLAY, "loadMagis() arranque caliente=${msArranque}ms → duracion=${duracion}ms")
        val item = PlayerData(
            episodeId = episodeId,
            itemId = episodeId.substringBefore("::"),
            title = cabecera?.itemTitle ?: efimero?.titulo?.takeIf { it.isNotBlank() } ?: "Magis",
            subtitle = cabecera?.episodeLabel.orEmpty(),
            mediaUrl = urlLocal,
            // Castear NO va a funcionar: el proxy escucha en 127.0.0.1 y la TV no llega ahí. Se deja
            // la URL directa para no romper el flujo; sin los headers el CDN devolverá 401.
            castUrl = play.url,
            artworkUrl = "",
            openingStartMs = null, openingEndMs = null, endingStartMs = null,
            kind = SourceKind.MAGIS,
            knownDurationMs = duracion,
            // Lo que DICE el portal, no lo que sugiere la extensión que el gateway le puso a la
            // URL: esa extensión colapsa a `.mp4` todo lo que no sea `ts` porque es la clave del
            // objeto en el CDN. Ver [com.arkiv.player.playback.formatoAvformatDe].
            contenedorDeLaFuente = play.container,
            // De acá lo lee [hayQueAnotarHistorial] en cada tick del reproductor. Es la segunda
            // vuelta de llave: la primera es que esto no tenga fila en la biblioteca.
            adulto = efimero?.adulto == true,
        )
        // Acá se forzaba SOFTWARE para el HEVC de magis, dando por hecho que el decodificador por
        // hardware descartaba las pistas (`pistas=v0/a0`). Ese diagnóstico era falso: el que las
        // descartaba era el subtítulo externo (ver PlayerScreen, donde magis no lo adjunta). Sin él,
        // el mismo título arranca con `v2/a3` por hardware y por software. Se deja abrir por
        // hardware —más rápido y sin gastar CPU—; si algún título de verdad falla ahí, el rescate
        // "hardware sin imagen → paso a software" de VlcPlayer sigue estando.
        //
        // Los subtítulos viajan por el MISMO canal que los de web: PlayerScreen decide qué hacer con
        // ellos. El portal los entrega junto al stream, así que no hay que ir a OpenSubtitles.
        _webExtras.value = WebExtras(
            episodeId,
            play.headers,
            play.subtitles.map {
                com.arkiv.player.data.catalog.web.ResolvedSub(lang = it.lang, url = it.url)
            },
        )
        // Lo efímero SIEMPRE arranca en cero, y no por olvido: no se guardó progreso, así que no hay
        // dónde reanudar. Es la consecuencia directa de la regla — no se puede retomar lo que
        // decidimos no anotar — y se prefiere eso a dejar el rastro.
        val startPos = if (efimero != null) 0L else safeStartPosition(episodeId, SourceKind.MAGIS)
        // REANUDAR: se le avisa al proxy A DÓNDE va a saltar el reproductor, para que prepare esa
        // zona mientras el video abre. libVLC abre siempre en el byte 0 y recién después busca el
        // minuto guardado: medido en el Fire TV, entre una cosa y la otra se bajaban 2,5 MB del
        // principio de la película que después se tiraban, y eso costaba 3,4 s con la imagen
        // congelada en el segundo 0. Ver ArchiveCacheProxy.precalentarSalto.
        //
        // La duración sale del progreso GUARDADO y no del gateway: acá el gateway suele mandar 0
        // (la duración la calcula VLC al abrir, que es demasiado tarde para esto), mientras que
        // quien ya vio un pedazo del capítulo tiene la duración anotada de esa vez.
        if (startPos > 0L) {
            val guardado = runCatching { repo.getPlayback(episodeId) }.getOrNull()
            val duracionGuardada = guardado?.durationMs ?: 0L
            if (duracionGuardada > 0L) {
                archiveCacheProxy.precalentarSalto(
                    play.url, play.headers, startPos.toFloat() / duracionGuardada,
                )
            }
        }
        // El arranque caliente ya está en la mano (se pidió arriba, en paralelo con la sonda): la
        // espera del CDN ocurrió ANTES de abrir el video, donde el usuario ve el spinner de
        // siempre, en vez de convertirse en un fallo del que no se vuelve.
        _playlist.value = PlaylistData(listOf(item), 0, startPos, pedido = episodeId)
        // RESUMEN, en una línea y en el orden en que se paga. Lo que falta para el primer frame es
        // lo que tarde VLC en abrir, que se mide aparte (ver el "abrió en Xms" de VlcPlayer): la
        // suma de las dos es lo que el usuario ve como spinner.
        Log.w(
            PLAY,
            "loadMagis() ⏱ TOTAL=${System.currentTimeMillis() - t0}ms " +
                "[resolve=${msResolve}ms | arranque=${msArranque}ms] startPos=$startPos",
        )
    }

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
        _playlist.value = PlaylistData(listOf(item), 0, startPos, pedido = episodeId)
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

    private fun buildData(
        episode: Episode,
        localUri: String?,
        marker: SkipMarkerEntity?,
        startPosMs: Long = 0L,
    ): PlayerData? {
        // URL http directa de archive (o null si no hay variante de streaming).
        val rawHttp = streamingVariant(episode)?.let { ArchiveUrls.download(episode.itemId, it.path) }
        // Archivo local completo (descarga terminada) -> se reproduce directo, sin proxy.
        // Streaming http de archive -> se envuelve con el proxy de caché en disco (VLC no puede
        // usar el CacheDataSource de media3), arrancándolo la primera vez.
        val mediaUrl = when {
            localUri != null -> localUri
            rawHttp != null -> {
                archiveCacheProxy.start()
                // Reanudando lejos del principio, el proxy tiene que bajar desde cerca de ese punto:
                // si baja desde 0, la lectura queda tan por delante de la caché que cada tramo va
                // directo al origen y la reproducción depende por completo de archive.org. Medido:
                // reanudando en 10:59 aguantaba 2:15 y se secaba; desde cero, continuo.
                val desde = VentanaDeDescarga.byteDeArranque(
                    startMs = startPosMs,
                    duracionMs = (episode.durationSeconds * 1000).toLong(),
                    total = streamingVariant(episode)?.sizeBytes ?: 0L,
                )
                ArchiveCacheProxy.conVentanaDesde(archiveCacheProxy.proxyUrl(rawHttp), desde)
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
            // Magis NO se precarga: cada resolución es una llamada al portal, que corta a 1 cada
            // 1.5 s. Gastarla en un capítulo que quizá no se vea retrasaría el que sí se está viendo.
            SourceKind.MAGIS -> Unit
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
            // Idem LIVE: repo.nextEpisode() nunca devuelve un id "live:" (no es un episodio de
            // ninguna serie) -- load() corta antes de programar este prefetch para un canal en
            // vivo (ver su guard), así que ni currentId llega acá con ese kind. El "siguiente" de
            // un canal en vivo es el zapping (LiveZapping), no esta precarga de series.
            SourceKind.LIVE -> Unit
        }
    }.onFailure { Log.w(PLAY, "prefetchNext falló: $it") }

    /** Cliente HTTP compartido para [warmHead]: evita crear un OkHttpClient (pool de hilos+conexiones) por episodio. */
    /**
     * Cliente del gateway. La URL se lee de [settings] en cada llamada. [httpGateway] viene por
     * constructor (Task 7b, ver su KDoc): es el `OkHttpClient` compartido de `AppGraph` con
     * `InterceptorDeSesion`, así que un 401/403 de identidad real cierra la sesión de la persona
     * aunque el pedido haya salido de acá y no de un ViewModel de pantalla.
     */
    private val gatewayClient by lazy {
        com.arkiv.player.data.gateway.ArkivApiClient(
            baseUrl = { settings.gatewayUrl.value },
            http = httpGateway,
            personToken = personToken,
            deviceToken = { deviceAuth.session.value?.token },
        )
    }

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
        precalentarJob?.cancel()
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
        // El progreso de contenido de adultos NO se escribe. `playback` es tabla sincronizada y de
        // ahí sale "seguir viendo", que se pinta en el inicio del televisor, en el del celular y en
        // la biblioteca: una fila acá no se queda quieta en este aparato. Ver
        // [hayQueAnotarHistorial], que es donde está la decisión y sus bordes.
        if (!_playlist.value.hayQueAnotarHistorial(episodeId)) return
        viewModelScope.launch { repo.savePlayback(episodeId, positionMs, durationMs) }
    }

    /**
     * Captura el frame que se está viendo. Best-effort y fuera del camino crítico: si no hay
     * TextureView o el frame no pasa las guardas, no pasa nada.
     *
     * El TextureView viaja como parámetro porque este ViewModel no tiene acceso al `VlcPlayer`
     * (vive en `PlayerScreen`, que sí puede leerlo con `vlc.textureViewActual()`); acá solo se
     * necesita `viewModelScope` para que la captura no bloquee el hilo de composición.
     */
    fun capturarFrame(episodeId: String, positionMs: Long, textureView: android.view.TextureView?) {
        // MISMO guarda que el progreso, y acá pesa más: un frame no es un número, es una imagen de
        // lo que se estaba viendo — y `FrameCapturer.publicar` escribe el JPEG en disco Y una fila
        // en `episode_frame`, que se sube a PocketBase y se propaga a los demás aparatos. Es la
        // fuga del 2026-08-14 otra vez, pero con foto.
        if (!_playlist.value.hayQueAnotarHistorial(episodeId)) return
        viewModelScope.launch { frameCapturer.capturar(episodeId, positionMs, textureView) }
    }

    private companion object {
        /** Identifier del ítem local para un capítulo de serie web (ver `addWebSeriesEpisode`). El
         * `seriesId` real (el que guarda Task 8 en `nuc_library_items`) es lo que queda DESPUÉS de
         * este prefijo. Se toma de [com.arkiv.player.data.SeriesItemIds] para no tener el literal
         * repetido en dos lugares que TIENEN que coincidir. */
        const val SERIES_ITEM_PREFIX = com.arkiv.player.data.SeriesItemIds.WEB_SERIES_PREFIX

        /** Tope de la espera de pre-buffer (ms): si el torrent es muy lento, se abre igual a los 30s. */
        const val PREBUFFER_CAP_MS = 30_000

        /** Cuántas veces se reabre un directo cortado antes de avisar. Ver [reabrirVivoPorCorte]. */
        const val MAX_REAPERTURAS_VIVO = 3

        /** Espera de la PRIMERA reapertura; las siguientes la duplican (2 s → 4 s → 8 s). */
        const val ESPERA_REAPERTURA_MS = 2_000L

        /**
         * Cuánto tiene que reproducir un canal reabierto para considerarlo recuperado y devolverle
         * el presupuesto entero de reaperturas. Ver [vivoAndando].
         */
        const val MINIMO_VIVO_SANO_MS = 5_000L

        /** Tag del gate de arranque torrent (filtrar con `adb logcat -s ArkivGate`). */
        const val GATE = "ArkivGate"

        /** Tag del flujo de carga/replay del player (filtrar con `adb logcat -s ArkivPlay`). */
        const val PLAY = "ArkivPlay"

        /** Colchón antes de precargar el próximo capítulo (dar aire al arranque del actual). */
        const val PREFETCH_DELAY_MS = 8_000L
    }
}
