package com.arkiv.player.ui.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.db.LiveRecentDao
import com.arkiv.player.data.db.LiveRecentEntity
import com.arkiv.player.data.db.SkipMarkerEntity
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.model.Episode
import com.arkiv.player.playback.ArchiveCacheProxy
import com.arkiv.player.playback.ContenidoDeAdultos
import com.arkiv.player.playback.DituVivo
import com.arkiv.player.playback.MagisEfimero
import com.arkiv.player.playback.PlayerSource
import com.arkiv.player.playback.SourceKind
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
    /** Posición de arranque para reanudar (ExoPlayer, p.ej. magisItem). VLC usa PlaylistData.startPositionMs. */
    val startPositionMs: Long = 0L,
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

/**
 * Un subtítulo resuelto (idioma + URL), para adjuntar como pista externa.
 *
 * Antes vivía en `com.arkiv.player.data.catalog.web.ResolvedSub` (torrent/web se borró en la poda de
 * esta rama); [WebExtras] la sigue necesitando porque también la usa magis, que recibe sus
 * subtítulos del gateway y no de ningún resolver web.
 */
data class ResolvedSub(val lang: String, val url: String)

/** Extras de una fuente resuelta (subtítulos + headers sniffeados) para adjuntar en la UI. Pese al
 *  nombre "web", también los usa [PlayerViewModel.loadMagis] para los subtítulos que trae el portal. */
data class WebExtras(
    val episodeId: String,
    val headers: Map<String, String>,
    val subtitles: List<ResolvedSub>,
)

/**
 * Lo que suena de Caracol: qué episodio es, desde dónde arrancar y lo que resolvió la fuente (la URL
 * del manifiesto y la licencia Widevine). Va en un solo valor para que la pantalla nunca vea la URL
 * de un episodio con la posición de otro.
 */
data class DituReproducible(
    val episodeId: String,
    val playable: com.arkiv.player.data.gateway.GatewayPlayable,
    val startPositionMs: Long = 0L,
    /**
     * Número de publicación; lo pone [EstadoDeDitu.publicar]. Existe para que dos publicaciones
     * nunca sean iguales: una recarga puede traer la misma URL y el mismo token, y la pantalla igual
     * tiene que rearmar el reproductor (lo compone dentro de un `key` con este valor entero).
     */
    val generacion: Int = 0,
)

/**
 * Qué se le muestra a la persona cuando un canal no abre.
 *
 * Antes esto adivinaba "este TV no está vinculado" mirando si la config del gateway seguía en el
 * default baked-in. En esta rama no hay gateway, y el motivo real por el que un canal no abre —el
 * único que la persona puede arreglar— es no tener cuenta de Magis vinculada: el portal rechaza el
 * vivo con sesión anónima (`aaa100028`), aunque el VOD ande perfecto con ella. De ahí que se
 * pregunte por la cuenta y no por el error del portal: es lo accionable, y no depende de parsear
 * mensajes ni códigos.
 */
fun mensajeErrorVivo(
    hayCuentaDeMagis: Boolean,
    nombreCanal: String,
): String =
    if (!hayCuentaDeMagis) {
        "El canal en vivo necesita una cuenta de Magis vinculada (con el VOD alcanza sin ella). " +
            "Vinculala en Ajustes, Cuenta."
    } else {
        "No se pudo abrir $nombreCanal"
    }

// `internal constructor` por [dituFuente]: su tipo es interno al módulo, y un constructor público no
// lo puede recibir.
class PlayerViewModel internal constructor(
    private val repo: ArkivRepository,
    private val archiveCacheProxy: ArchiveCacheProxy,
    private val localLibrary: com.arkiv.player.data.local.LocalLibrary,
    private val localFileServer: com.arkiv.player.playback.LocalFileServer,
    private val frameCapturer: com.arkiv.player.miniaturas.FrameCapturer,
    // Tarea 14 (modo vivo): pegados al final para no reordenar los parámetros posicionales de
    // arriba (el callsite en PlayerScreen los pasa por posición, no por nombre).
    private val liveController: LiveController,
    private val liveRecentDao: LiveRecentDao,
    // ¿Este proceso corre en un Android TV? Lo leen las pantallas que se dibujan distinto.
    private val esTelevision: Boolean = false,
    // Sub-proyecto 2A: de acá sale lo reproducible, directo del portal.
    private val fuente: com.arkiv.player.data.gateway.FuenteDeContenido,
    // Caracol aparte de [fuente]: sus canales en vivo no son parte del contrato común (ver
    // `AppGraph.dituFuente`). [loadDitu] los resuelve con `DituFuente.resolverCanal`.
    private val dituFuente: com.arkiv.player.data.ditu.DituFuente,
    /** Si hay una cuenta de Magis vinculada en este aparato. Solo decide qué dice el error cuando
     *  un canal en vivo no abre (ver [mensajeErrorVivo]): el vivo la exige, el VOD no. */
    private val hayCuentaDeMagis: () -> Boolean = { false },
) : ViewModel() {

    private val _playlist = MutableStateFlow<PlaylistData?>(null)
    val playlist: StateFlow<PlaylistData?> = _playlist.asStateFlow()

    /** Ítem de Magis — lo reproduce ExoPlayer a través del proxy local, sin pasar por VLC. */
    private val _magisItem = MutableStateFlow<PlayerData?>(null)
    val magisItem: StateFlow<PlayerData?> = _magisItem.asStateFlow()

    /**
     * Canal en vivo (Task 1, poda de light-magis) — lo reproduce ExoPlayer a través de
     * [LiveHlsProxy], sin pasar por VLC. Reemplaza a [_playlist] para [SourceKind.LIVE]: antes de
     * esta tarea [abrirCanalActual] publicaba un `PlaylistData` de un solo ítem para que VLC lo
     * reprodujera, igual que hacía Magis VOD antes de su propia migración (ver [_magisItem]).
     *
     * `startPositionMs` siempre es 0 -- un directo no tiene "dónde ibas" (ver el KDoc de
     * [abrirCanalActual]), así que a diferencia de [_magisItem] este ítem no necesita reanudación.
     */
    private val _liveItem = MutableStateFlow<PlayerData?>(null)
    val liveItem: StateFlow<PlayerData?> = _liveItem.asStateFlow()

    /**
     * Episodio de Caracol en curso, o `null` si lo que suena es de otra fuente. Cuando no es null,
     * `PlayerScreen` lo reproduce con [DituExoPlayer] en vez de VLC o del reproductor de Magis.
     * Lo publica [EstadoDeDitu], que descarta lo que llega tarde y lleva los topes de re-preparados
     * y de recargas.
     */
    private val ditu = EstadoDeDitu()
    val dituPlayable: StateFlow<DituReproducible?> = ditu.actual

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

    // Feedback mientras el resolver de blog snifea el stream de una fuente web (puede tardar).
    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    // Subtítulos + headers sniffeados de la fuente web, para que PlayerScreen los adjunte.
    private val _webExtras = MutableStateFlow<WebExtras?>(null)
    val webExtras: StateFlow<WebExtras?> = _webExtras.asStateFlow()

    /** Job cancelable de la precarga del siguiente capítulo (torrent pack / web / archive). */
    private var prefetchJob: kotlinx.coroutines.Job? = null

    /** Carga el episodio como playlist, ramificando por fuente (archive vs torrent vs web). */
    fun load(episodeId: String) {
        // Antes que todo lo demás, y también para el vivo: una resolución de Caracol que siga en
        // vuelo tiene que saber que ya no es la vigente. Ver [EstadoDeDitu].
        ditu.nuevoPedido(episodeId)
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
            //
            // Un canal en vivo de Caracol tampoco se anota: no tiene fila en la biblioteca ni nada
            // que reanudar, y `marcarEnCurso` escribiría igual una fila en `playback` con su id.
            if (!DituVivo.esVivo(episodeId) &&
                ContenidoDeAdultos.hayQueAnotar(MagisEfimero.tomar(episodeId)?.adulto)
            ) {
                runCatching { repo.marcarEnCurso(episodeId) }
            }
            _error.value = null
            _magisItem.value = null
            // Navegar de un canal en vivo a un episodio VOD sin pasar por otra pantalla (el mismo
            // ViewModel sobrevive, ver el guard de más arriba): sin este reset, `liveItem` seguía
            // publicando el último canal y PlayerScreen (isLive/isLiveExo) lo creía vigente.
            _liveItem.value = null
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
                SourceKind.ARCHIVE -> loadArchive(episodeId)
                SourceKind.MAGIS -> loadMagis(episodeId)
                SourceKind.DITU -> loadDitu(episodeId)
                // PlayerSource.kindFor() nunca devuelve NUC ni LOCAL (ver su propio KDoc): esta rama
                // es inalcanzable por diseño, pero el `when` exhaustivo la exige. Apunta a loadWeb()
                // porque es el único filler que sigue existiendo (NUC/PlaybackPreferenceStore se
                // borraron en la poda de Task 8).
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
     * Abre el canal actual del zapping: resuelve contra [liveController] y publica un [PlayerData]
     * de UN solo ítem que arranca siempre en 0 -- en vivo no hay "dónde ibas" que reanudar. NO
     * sondea duración (no la hay) y NO guarda progreso (ver [saveProgress], que PlayerScreen ya no
     * llama en modo vivo). Anota el canal en [liveRecentDao] -- es lo único que llena el chip
     * "Recientes" de la grilla, que hasta esta tarea nadie escribía.
     *
     * Task 1 (poda de light-magis): publica [_liveItem], no [_playlist] -- el canal en vivo lo
     * reproduce ExoPlayer a través de [LiveHlsProxy] (mismo patrón que [_magisItem] para VOD), sin
     * pasar por VLC. `url` ya sale de [liveController] con el host/puerto/token del proxy local
     * inyectados; ExoPlayer no necesita headers propios porque el proxy los pone él mismo contra el
     * CDN -- es la razón de ser de [LiveHlsProxy] (ver su KDoc).
     */
    private fun abrirCanalActual() {
        val canal = zapping?.actual ?: return
        _liveCanal.value = canal
        viewModelScope.launch {
            _error.value = null
            errorDeReproduccion = false
            // Fix de revisión (Task 1): igual que loadMagis()/loadWeb() descartan la
            // fuente VOD rival ANTES de publicar la propia, acá hay que descartar TODAS las fuentes
            // VOD antes de publicar `_liveItem`. Sin esto, entrar en vivo sin recomponer la pantalla
            // (irACanal()/zapSiguiente()/zapAnterior() llaman a abrirCanalActual() directo, sin pasar
            // por el reset de load()) dejaba `_magisItem`/`_playlist` con el valor
            // viejo. PlayerScreen.activePlayer mira magisItem ANTES que liveItem, así que
            // un `_magisItem` viejo ganaría esa decisión y el canal en vivo nunca se vería -- y un
            // `_playlist` viejo podía reactivar el `LaunchedEffect(playlist, generacionVivo)` de VOD
            // contra contenido ya abandonado.
            _playlist.value = null
            _magisItem.value = null
            ditu.limpiar()
            val url = runCatching { liveController.abrir(canal.code) }.getOrElse {
                Log.w(PLAY, "abrirCanalActual() falló para ${canal.code}: ${it.message}")
                if (zapping?.actual?.code == canal.code) {
                    _error.value = mensajeErrorVivo(hayCuentaDeMagis(), canal.nombre)
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
            // Sin `pedido` (a diferencia del viejo PlaylistData): en vivo nunca pasa por
            // MediaReusePolicy, que era el único consumidor de esa marca.
            _liveItem.value = item
            // Y el aviso de que ACÁ HUBO UNA CARGA, aunque el valor de arriba sea idéntico al que
            // ya estaba. Reabrir un canal cortado produce un [PlayerData] **igual** al anterior
            // -mismo canal, y `mediaUrl` es la url del proxy local, cuyo puerto y token viven
            // tanto como el socket-, y un `StateFlow` descarta los valores iguales: la pantalla no
            // se enteraba, no volvía a recargar el MediaItem, y la reapertura quedaba en el log
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

    /**
     * ELIMINADA en la poda de esta rama (borrado de archive.org, ver CLAUDE.md "Cero servidor
     * propio"): armaba la sección completa de un ítem de archive.org como playlist, resolviendo
     * cada episodio con [buildData] (local, o vía el proxy de caché en disco -hoy borrado-).
     *
     * Se conserva la función -no se borra del todo- porque todavía la llama [load] para
     * SourceKind.ARCHIVE (filas viejas de la biblioteca, de antes de esta rama, siguen marcadas
     * así), así que hace falta algo que siga compilando en su lugar. Reporta el error limpio en vez
     * de intentar reproducir.
     */
    private fun loadArchive(episodeId: String) {
        Log.w(PLAY, "loadArchive() episodeId=$episodeId → fuente archive.org eliminada de esta rama")
        _playlist.value = null
        _webExtras.value = null
        _resolving.value = false
        _error.value = "Esta fuente ya no está disponible en esta versión"
    }

    fun onMagisExoError(message: String) {
        _error.value = "Magis: $message"
    }

    /**
     * [DituExoPlayer] se rindió: agotó sus re-preparados, o el error no era de los que se arreglan
     * así. Antes de avisar se le pide a Caracol una URL nueva —trae otro `playback_token`— y se
     * retoma en [posicionMs]. Con tope: ver [EstadoDeDitu.pedirRecarga].
     */
    fun onDituExoError(message: String, posicionMs: Long) {
        val episodio = ditu.pedirRecarga()
        if (episodio == null) {
            _error.value = "Caracol: $message"
            return
        }
        Log.w(PLAY, "Caracol: $message → pido una URL nueva para $episodio desde ${posicionMs}ms")
        viewModelScope.launch { loadDitu(episodio, arrancarEnMs = posicionMs) }
    }

    /** [DituExoPlayer] tuvo un error que se arregla volviendo a preparar: ¿queda alguno? Ver
     *  [EstadoDeDitu.pedirRepreparado]. */
    fun dituPuedeRepreparar(): Boolean = ditu.pedirRepreparado()

    /** Cada lectura del reloj de [DituExoPlayer]. Ver [EstadoDeDitu.avanzo]. */
    fun dituAvanzo(posicionMs: Long, reproduciendo: Boolean) = ditu.avanzo(posicionMs, reproduciendo)

    /**
     * Un directo se cayó del lado de ExoPlayer (segmento/playlist en 502 tras agotar los
     * reintentos del proxy, o cualquier otro `PlaybackException`).
     *
     * A diferencia de [onMagisExoError], NO pone `_error` directo: un canal en
     * vivo se recupera solo casi siempre (ver KDoc de [reabrirVivoPorCorte]), así que mostrar un
     * cartel de error en el primer tropiezo sería alarmar por algo que en 2-8s ya se resolvió.
     * [reabrirVivoPorCorte] es quien decide, tras [MAX_REAPERTURAS_VIVO] intentos, si hay que
     * avisarle a la persona.
     */
    fun onLiveExoError(message: String) {
        Log.w(PLAY, "vivo (exo) error para ${zapping?.actual?.code}: $message")
        reabrirVivoPorCorte()
    }

    /**
     * El reproductor no pudo con este episodio.
     *
     * Antes esto no existía: un fallo de reproducción no llegaba nunca a [_error] —que es lo único
     * que la pantalla pinta— así que la película simplemente no arrancaba y no aparecía ningún
     * mensaje. Medido el 2026-08-10: `EncounteredError` en el log y `error=false` en la UI.
     *
     * Hasta la poda de archive.org de esta rama había acá un camino de auto-reparación para el 404
     * (la app cacheaba el nombre del archivo y, si archive.org lo renombraba, refrescaba la
     * metadata y volvía a ubicar el capítulo — ver [sanarRenombre] en el historial). Borrado junto
     * con el resto de esa fuente: ya no hay archive.org al que preguntarle nada.
     */
    fun onPlaybackFailed(episodeId: String) {
        // Todo lo que se escriba de acá para abajo describe un tropiezo del player, no una
        // fuente que no se pudo abrir: si el video remonta, deja de ser cierto. Ver
        // [errorDeReproduccion].
        errorDeReproduccion = true
        _error.value = "No se pudo reproducir este capítulo"
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
     * Reproduce un ítem de Magis.
     *
     * El CDN exige `Content-Auth` y `Content-License`, y libVLC solo sabe mandar Referer y
     * User-Agent: por eso el stream va por el proxy local, que sí puede ponerlos en la petición al
     * origen. El [ref] guardado se manda tal cual a `MagisResolve.resolveVod`; la app nunca lo
     * interpreta.
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
        val resuelto = withContext(Dispatchers.IO) { runCatching { fuente.resolve(ref) } }
        val msResolve = System.currentTimeMillis() - t0
        _resolving.value = false
        Log.w(PLAY, "loadMagis() resolve del portal=${msResolve}ms")

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
        // ellos. El portal los entrega junto al stream, así que no hace falta pedirlos aparte a
        // ningún catálogo de subtítulos.
        _webExtras.value = WebExtras(
            episodeId,
            play.headers,
            play.subtitles.map { ResolvedSub(lang = it.lang, url = it.url) },
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
        _magisItem.value = item.copy(startPositionMs = startPos)
        // RESUMEN, en una línea y en el orden en que se paga. Lo que falta para el primer frame es
        // lo que tarde VLC en abrir, que se mide aparte (ver el "abrió en Xms" de VlcPlayer): la
        // suma de las dos es lo que el usuario ve como spinner.
        Log.w(
            PLAY,
            "loadMagis() ⏱ TOTAL=${System.currentTimeMillis() - t0}ms " +
                "[resolve=${msResolve}ms | arranque=${msArranque}ms] startPos=$startPos",
        )
    }

    /**
     * Reproduce un episodio de Caracol, o uno de sus canales en vivo.
     *
     * A diferencia de [loadMagis], no pasa por [archiveCacheProxy]: los headers que pide Caracol los
     * pone el propio [DituExoPlayer]. Acá solo se resuelve y se publica en [dituPlayable], junto con
     * la posición desde donde reanudar.
     *
     * El `ref` sale de [ArkivRepository.magisRefForEpisode], que pese al nombre lee el ref guardado
     * en la fila del episodio (o, si no tiene, en la de su ítem) sin mirar de qué fuente es.
     *
     * Un canal en vivo ([DituVivo.esVivo]) no tiene fila en la biblioteca ni `ref`: se resuelve el
     * canal que dejó la sección de Caracol, con `DituFuente.resolverCanal`, y pasa por las mismas
     * guardas de [EstadoDeDitu] que el VOD. Una recarga ([onDituExoError]) vuelve a entrar por acá
     * con el mismo `episodeId` y resuelve el canal otra vez: por eso [DituVivo.tomar] no lo vacía.
     *
     * [arrancarEnMs] es para las recargas: se retoma donde iba y no desde la posición guardada. Sin
     * él, la misma reanudación que Magis. Un vivo arranca siempre en 0, y con 0 [DituExoPlayer] no
     * hace `seekTo`: queda en la posición por defecto del directo.
     */
    private suspend fun loadDitu(episodeId: String, arrancarEnMs: Long? = null) {
        val vivo = DituVivo.esVivo(episodeId)
        val canal = if (vivo) DituVivo.tomar(episodeId) else null
        val ref = if (vivo) null else repo.magisRefForEpisode(episodeId)
        Log.w(
            PLAY,
            "loadDitu() episodeId=$episodeId ref=${ref?.take(16)}… canal=${canal?.channelId} " +
                "recarga=${arrancarEnMs != null}",
        )
        // Lo que sigue toca estado que comparten todas las fuentes: si mientras se leía el ref ya se
        // pidió otro episodio, esto no es de nadie. Ver [EstadoDeDitu].
        if (!ditu.esVigente(episodeId)) return
        val resolver: suspend () -> com.arkiv.player.data.gateway.GatewayPlayable = when {
            canal != null -> suspend { dituFuente.resolverCanal(canal) }
            !ref.isNullOrBlank() -> suspend { fuente.resolve(ref) }
            else -> {
                _error.value = if (vivo) "No se encontró el canal de Caracol" else "No se encontró la fuente de Caracol"
                return
            }
        }

        _playlist.value = null
        _webExtras.value = null
        _resolving.value = true
        val resuelto = withContext(Dispatchers.IO) { runCatching { resolver() } }
        // Se apaga aunque ya no sea el vigente: si lo que se pidió después es un canal en vivo,
        // ese camino no toca esta bandera y quedaría prendida.
        _resolving.value = false
        if (!ditu.esVigente(episodeId)) {
            Log.w(PLAY, "loadDitu() descartado: $episodeId ya no es el pedido vigente")
            return
        }
        val play = resuelto.getOrNull()
        if (play == null) {
            Log.w(PLAY, "loadDitu() falló: ${resuelto.exceptionOrNull()?.message}")
            _error.value = resuelto.exceptionOrNull()?.message ?: "No se pudo reproducir en Caracol"
            return
        }
        // La misma reanudación que Magis: [safeStartPosition] sobre el progreso guardado. Un vivo no
        // tiene "dónde ibas", ni siquiera en una recarga.
        val startPos = if (vivo) 0L else arrancarEnMs ?: safeStartPosition(episodeId, SourceKind.DITU)
        Log.w(PLAY, "loadDitu() drm=${play.drmLicenseUrl.isNotBlank()} startPos=$startPos")
        // `publicar` vuelve a mirar si sigue vigente: `safeStartPosition` también suspende.
        if (!ditu.publicar(DituReproducible(episodeId, play, startPos))) {
            Log.w(PLAY, "loadDitu() descartado al publicar: $episodeId ya no es el pedido vigente")
        }
    }

    /**
     * ELIMINADA en la poda de esta rama (borrado de torrent+web+mirror, ver CLAUDE.md "Cero servidor
     * propio"): resolvía una `pageUrl` scrapeada on-device contra `WebResolverApi` (el resolver
     * headless de blog), que ya no existe. Se conserva la función -no se borra del todo- porque
     * el filler NUC/LOCAL de [load] todavía la llama (esa rama es inalcanzable por diseño, ver el
     * KDoc de [load], pero el `when` exhaustivo la exige), así que hace falta algo que siga
     * compilando en su lugar. Reporta el error limpio en vez de intentar reproducir.
     */
    private fun loadWeb(episodeId: String) {
        Log.w(PLAY, "loadWeb() episodeId=$episodeId → fuente web eliminada de esta rama")
        _playlist.value = null
        _webExtras.value = null
        _resolving.value = false
        _error.value = "Esta fuente ya no está disponible en esta versión"
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
     * ELIMINADA en la poda de esta rama (borrado de archive.org, ver CLAUDE.md "Cero servidor
     * propio"): armaba el [PlayerData] de un episodio de archive.org, local o vía el proxy de
     * caché en disco de [ArchiveCacheProxy] (ese modo del proxy se borró junto con esta función;
     * ver su KDoc). Se conserva -no se borra del todo- porque todavía la llaman [loadArchive] y
     * [prefetchNext] -maquinaria de la sección archive de la biblioteca que esta tarea no arranca
     * de raíz-, así que hace falta algo que siga compilando en su lugar. SIEMPRE null: no hay nada
     * que reproducir.
     */
    private fun buildData(
        episode: Episode,
        localUri: String?,
        marker: SkipMarkerEntity?,
        startPosMs: Long = 0L,
    ): PlayerData? = null

    /** Precarga el SIGUIENTE episodio de la serie en segundo plano (archive). Best-effort. */
    private suspend fun prefetchNext(currentId: String) = runCatching {
        kotlinx.coroutines.delay(PREFETCH_DELAY_MS)
        val next = repo.nextEpisode(currentId) ?: return@runCatching
        when (PlayerSource.kindFor(next.id)) {
            // Magis NO se precarga: cada resolución es una llamada al portal, que corta a 1 cada
            // 1.5 s. Gastarla en un capítulo que quizá no se vea retrasaría el que sí se está viendo.
            SourceKind.MAGIS -> Unit
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
            // Caracol tampoco se precarga: resolver es pedirle a su API el detalle, el permiso y la
            // URL (ver DituResolve.vod) por un capítulo que quizá no se vea, y nada acá guarda el
            // resultado para usarlo después.
            SourceKind.DITU -> Unit
        }
    }.onFailure { Log.w(PLAY, "prefetchNext falló: $it") }

    /** La corrección a mano de los tiempos, del capítulo en curso o de la serie. Ver su KDoc. */
    private val editorDeMarcadores by lazy {
        com.arkiv.player.data.marcadores.EditorDeMarcadores(dao = repo.skipMarkerDao())
    }

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
        precalentarJob?.cancel()
        super.onCleared()
    }

    /**
     * Marca a mano el fin del opening en [ms].
     *
     * [episodeId] dice a QUÉ se le pone: un capítulo, o `""` = la serie entera (lo que hacía
     * siempre este camino). Poder marcar UN capítulo es lo que hace usable la corrección: con
     * marcadores automáticos por capítulo, un manual de serie le pisa el automático correcto a
     * todos los demás (ver [EditorDeMarcadores]).
     */
    fun setOpeningEnd(ms: Long, episodeId: String = "") = editarMarcador(episodeId) { itemId ->
        editorDeMarcadores.finDelOpening(itemId, episodeId, ms)
    }

    /** Marca a mano el inicio del ending en [ms]. Ver [setOpeningEnd] para [episodeId]. */
    fun setEndingStart(ms: Long, episodeId: String = "") = editarMarcador(episodeId) { itemId ->
        editorDeMarcadores.inicioDelEnding(itemId, episodeId, ms)
    }

    /** "Esto no tiene intro ni outro". Ver [setOpeningEnd] para [episodeId]. */
    fun clearMarkers(episodeId: String = "") = editarMarcador(episodeId) { itemId ->
        editorDeMarcadores.quitar(itemId, episodeId)
    }

    private fun editarMarcador(episodeId: String, bloque: suspend (String) -> Unit) {
        val itemId = _playlist.value?.items?.firstOrNull()?.itemId ?: return
        viewModelScope.launch {
            bloque(itemId)
            // La copia horneada en la playlist: la pantalla lee los marcadores de Room (por eso
            // salen sin recargar nada), pero estos campos siguen alimentando el panel-editor y lo
            // que se le manda al receptor, así que se dejan al día con lo que quedó guardado.
            val guardado = repo.getSkipMarker(itemId, episodeId)
            val actual = _playlist.value ?: return@launch
            _playlist.value = actual.copy(
                items = actual.items.map {
                    if (episodeId.isNotEmpty() && it.episodeId != episodeId) {
                        it
                    } else {
                        it.copy(
                            openingStartMs = guardado?.openingStartMs,
                            openingEndMs = guardado?.openingEndMs,
                            endingStartMs = guardado?.endingStartMs,
                        )
                    }
                },
            )
        }
    }

    fun saveProgress(episodeId: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        // Un canal en vivo de Caracol no guarda progreso. `PlayerScreen` deja afuera el vivo con
        // `enVivo`, que es solo el de Magis (`SourceKind.LIVE`): el de Caracol es `SourceKind.DITU`,
        // y `repo.savePlayback` escribe la fila aunque no haya episodio en la biblioteca.
        if (DituVivo.esVivo(episodeId)) return
        // El progreso de contenido de adultos NO se escribe. `playback` es tabla sincronizada y de
        // ahí sale "seguir viendo", que se pinta en el inicio del televisor, en el del celular y en
        // la biblioteca: una fila acá no se queda quieta en este aparato. Ver
        // [hayQueAnotarHistorial], que es donde está la decisión y sus bordes.
        // Magis ExoPlayer: el ítem está en _magisItem, no en _playlist.
        // Caracol cae en la rama de abajo: `loadDitu` deja `_playlist` en null, y con eso
        // [hayQueAnotarHistorial] anota.
        val magisIt = _magisItem.value?.takeIf { it.episodeId == episodeId }
        if (magisIt != null) {
            if (!ContenidoDeAdultos.hayQueAnotar(magisIt.adulto)) return
        } else {
            if (!_playlist.value.hayQueAnotarHistorial(episodeId)) return
        }
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
        val magisIt = _magisItem.value?.takeIf { it.episodeId == episodeId }
        if (magisIt != null) {
            if (!ContenidoDeAdultos.hayQueAnotar(magisIt.adulto)) return
        } else {
            if (!_playlist.value.hayQueAnotarHistorial(episodeId)) return
        }
        viewModelScope.launch { frameCapturer.capturar(episodeId, positionMs, textureView) }
    }

    private companion object {
        /** Cuántas veces se reabre un directo cortado antes de avisar. Ver [reabrirVivoPorCorte]. */
        const val MAX_REAPERTURAS_VIVO = 3

        /** Espera de la PRIMERA reapertura; las siguientes la duplican (2 s → 4 s → 8 s). */
        const val ESPERA_REAPERTURA_MS = 2_000L

        /**
         * Cuánto tiene que reproducir un canal reabierto para considerarlo recuperado y devolverle
         * el presupuesto entero de reaperturas. Ver [vivoAndando].
         */
        const val MINIMO_VIVO_SANO_MS = 5_000L

        /** Tag del flujo de carga/replay del player (filtrar con `adb logcat -s ArkivPlay`). */
        const val PLAY = "ArkivPlay"

        /** Colchón antes de precargar el próximo capítulo (dar aire al arranque del actual). */
        const val PREFETCH_DELAY_MS = 8_000L
    }
}
