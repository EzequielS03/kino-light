package com.arkiv.player.ui.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.db.LiveRecentDao
import com.arkiv.player.data.db.LiveRecentEntity
import com.arkiv.player.data.ditu.CaracolFailure
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.playback.ArchiveCacheProxy
import com.arkiv.player.playback.AdultContent
import com.arkiv.player.playback.DituLive
import com.arkiv.player.playback.MagisEphemeral
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
    val kind: SourceKind,       // source (MAGIS/DITU/LOCAL/LIVE/UNKNOWN) -- PlayerScreen reads it for live detection, the cast LAN URL and the cast-transcode origin
    val referer: String? = null,    // headers para el stream web (algunos hosts exigen Referer)
    val userAgent: String? = null,
    val proxyUrl: String? = null,   // web: URL proxeada de respaldo si la directa falla (403/geo/anti-leech)
    val preferirSoftware: Boolean = false, // HEVC de magis: el hardware falla y deja sin pistas. Ver PlayerSourceTag.
    /**
     * Si esto vino de una sección de adultos: nada de lo que suene con esta marca se anota en el
     * historial. Ver [com.arkiv.player.playback.AdultContent] y [hayQueAnotarHistorial].
     *
     * Viaja en el ÍTEM y no se consulta al vuelo por dos motivos. Uno, el ítem es lo único que
     * llega hasta acá: `saveProgress` recibe un `episodeId` pelado y no tiene de dónde deducir de
     * qué sección salió. Y dos, el contenido de adultos NO tiene fila en la biblioteca —esa es toda
     * la idea—, así que no hay a quién preguntarle después.
     *
     * `false` by default on purpose: it's the right value for every source that isn't the Magis
     * catalog (Caracol/Ditu, local, live, or a legacy id from a source removed in this branch's
     * pruning -- archive, torrent, web), where the notion doesn't exist.
     */
    val adulto: Boolean = false,
    /**
     * Start position to resume (ExoPlayer, e.g. magisItem). The local player uses
     * PlaylistData.startPositionMs instead.
     */
    val startPositionMs: Long = 0L,
)

/**
 * ¿Hay que anotar el progreso de [episodeId] en el historial?
 *
 * La pregunta se contesta contra la playlist que está sonando porque `saveProgress` recibe un
 * `episodeId` pelado, no un ítem. Vive acá afuera —y no dentro del ViewModel— por lo mismo que
 * [com.arkiv.player.playback.AdultContent] vive afuera de `savePlayback`: es donde se pueden
 * fijar sus bordes con tests.
 *
 * El borde que importa es el episodio que NO está en la playlist, y se resuelve ANOTANDO. No es un
 * caso teórico: el ViewModel sobrevive a la navegación entre capítulos y `_playlist` sigue
 * publicando la del capítulo anterior mientras la fuente nueva resuelve (ver [PlaylistData.pedido]),
 * así que hay ventanas de segundos donde el episodio preguntado todavía no está. Leer eso como "es
 * adulto" dejaría de guardar el progreso de contenido normal en silencio.
 *
 * Un canal en vivo de Caracol ([DituLive]) no se anota nunca: no tiene fila en la biblioteca ni nada
 * que reanudar. `PlayerScreen` ya no le guarda la posición (su `enVivo` sale de
 * `PlayerSource.isLiveChannel`, que lo incluye), pero su captura al pausar no mira `enVivo`, y en
 * `saveProgress`/`capturarFrame` no entra por la rama de `_magisItem`: esta sigue siendo la guarda
 * que lo frena en los dos.
 */
internal fun PlaylistData?.hayQueAnotarHistorial(episodeId: String): Boolean =
    !DituLive.isLive(episodeId) &&
        AdultContent.shouldLog(this?.items?.firstOrNull { it.episodeId == episodeId }?.adulto)

/**
 * ¿Hay que marcar [episodeId] como "en curso" al abrirlo (`ArkivRepository.markInProgress`)?
 *
 * Es la decisión de `PlayerViewModel.load`, acá afuera para poder fijarla con tests. [adulto] es el
 * del pendiente efímero ([MagisEphemeral]), lo único que se sabe antes de resolver la fuente, y lo que
 * no se sabe se anota, igual que en [AdultContent.shouldLog]. Un canal en vivo de Caracol no
 * se marca: `markInProgress` escribiría una fila en `playback` con su id aunque no haya episodio.
 */
internal fun hayQueMarcarEnCurso(episodeId: String, adulto: Boolean?): Boolean =
    !DituLive.isLive(episodeId) && AdultContent.shouldLog(adulto)

/**
 * The section as a playlist: every episode + where/how to start. archive.org (removed in this
 * branch's pruning) was the only source that ever produced more than one item here -- every source
 * today (Magis, Ditu, live, local, and the legacy torrent/web rows) publishes a single-item
 * `PlaylistData(listOf(item), …)`. See `OutroSkip`'s KDoc in OutroSkip.kt.
 */
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
     * NO es "el capítulo que suena ahora" (eso lo responde `episodioEnCurso` en PlayerScreen): the
     * distinction dates back to archive.org, which used to load the whole section and let the
     * player advance on its own within it without asking again. No source does that today (see
     * this class's own KDoc), but `pedido` still exists for the survives-navigation race above.
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
    /**
     * Si el reproductor arranca solo con la primera imagen. `false` es una recarga de algo que estaba
     * en pausa: por ejemplo, un video que se pausó al irse la app al fondo y falló allá. `PlayerScreen`
     * lee esto con `collectAsStateWithLifecycle`, así que ese reproductor nuevo se arma recién al
     * volver, y no puede arrancar a sonar solo. Ver [StartOnFirstFrame.wantedToPlay].
     */
    val arrancarSolo: Boolean = true,
    /**
     * El capítulo ya está bajado al dispositivo: de dónde leerlo.
     *
     * `null` = reproducir por streaming, como siempre. Cuando viene, los segmentos salen del caché
     * en vez del CDN -- pero la LICENCIA se sigue pidiendo por red, porque Caracol no concede
     * licencias persistentes (ver [com.arkiv.player.data.caracol.CaracolDownload]). Por eso esto
     * convive con [playable] en lugar de reemplazarlo: de ahí sale el `playback_token` fresco.
     */
    val descargaLocal: com.arkiv.player.data.caracol.CaracolDownload? = null,
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
            "Vincúlala en Ajustes, Cuenta."
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
    private val frameCapturer: com.arkiv.player.thumbnails.FrameCapturer,
    // Tarea 14 (modo vivo): pegados al final para no reordenar los parámetros posicionales de
    // arriba (el callsite en PlayerScreen los pasa por posición, no por nombre).
    private val liveController: LiveController,
    private val liveRecentDao: LiveRecentDao,
    // ¿Este proceso corre en un Android TV? Lo leen las pantallas que se dibujan distinto.
    private val esTelevision: Boolean = false,
    // Sub-proyecto 2A: de acá sale lo reproducible, directo del portal.
    private val fuente: com.arkiv.player.data.gateway.ContentSource,
    // Caracol aparte de [fuente]: sus canales en vivo no son parte del contrato común (ver
    // `AppGraph.dituFuente`). [loadDitu] los resuelve con `DituFuente.resolverCanal`.
    private val dituFuente: com.arkiv.player.data.ditu.DituFuente,
    /** Si hay una cuenta de Magis vinculada en este aparato. Solo decide qué dice el error cuando
     *  un canal en vivo no abre (ver [mensajeErrorVivo]): el vivo la exige, el VOD no. */
    private val hayCuentaDeMagis: () -> Boolean = { false },
    /** El dato curioso (sub-proyecto 4). Null en los tests que no lo usan: sin él no hay botón. */
    private val datosCuriosos: com.arkiv.player.data.trivia.TriviaFacts? = null,
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

    /** Resolution error (no source found on Magis/Caracol, a legacy id from a removed source, etc.) for the screen to show. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Si lo que hay en [_error] vino de un TROPIEZO del player y no de no poder abrir la fuente.
     *
     * Son dos situaciones distintas que hasta ahora compartían canal. "No hay peers" o "no se pudo
     * resolver la fuente" significan que NO hay nada sonando: el cartel tiene que quedarse. En
     * cambio [onPlaybackFailed] se dispara con un PlaybackException, y de esos hay que se reparan
     * solos —un tirón de red, un rebuffer que el player remonta— con el video siguiendo de largo. Ahí el
     * cartel queda mintiendo sobre un video que anda bien, y encima tapa los controles: la barra se
     * compone con `loadError == null`, así que mientras esté en pantalla el D-pad no llega al
     * slider y no se puede ni pausar. Visto en el Fire TV el 2026-08-12.
     *
     * Ver [onReproduccionViva], que es quien lo apaga.
     */
    private var errorDeReproduccion = false

    // Feedback while Magis/Caracol resolve the actual playable stream (can take a moment). Named
    // after the removed web resolver this originally covered; Magis and Ditu are what set it today.
    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    // Subtitles + headers for PlayerScreen to attach. Named "web" from the removed web source;
    // today it's Magis's own subtitle languages coming from the gateway (see WebExtras's KDoc).
    private val _webExtras = MutableStateFlow<WebExtras?>(null)
    val webExtras: StateFlow<WebExtras?> = _webExtras.asStateFlow()

    /**
     * Datos curiosos de lo que se está viendo, o vacío. Se piden TODOS DE UNA al arrancar y la
     * pantalla avanza entre ellos a pulsación (ver [TriviaDelPlayer]): pasar al siguiente no puede
     * costar los ~20 s que tarda el modelo, ni fallar a mitad de una película.
     */
    private val _trivia = MutableStateFlow<List<String>>(emptyList())
    val trivia: StateFlow<List<String>> = _trivia.asStateFlow()

    /** Cancelable: al saltar de capítulo, la tanda del anterior ya no sirve. */
    private var triviaJob: kotlinx.coroutines.Job? = null

    /** Carga el episodio como playlist, ramificando por fuente (Magis vs Ditu vs id desconocido/legado). */
    fun load(episodeId: String) {
        // Antes que todo lo demás, y también para el vivo: una resolución de Caracol que siga en
        // vuelo tiene que saber que ya no es la vigente. Ver [EstadoDeDitu].
        ditu.nuevoPedido(episodeId)
        apagarTrivia()
        // Modo vivo (Tarea 14): CORTA ACÁ, antes de tocar nada del camino VOD de abajo -- ni
        // markInProgress ni localLibrary. Es la bandera que aísla TODO el comportamiento distinto:
        // un canal en vivo no tiene duración que sondear (ver KDoc de LiveZapping/LiveController --
        // sondearla es lo que rompía el VOD de Magis), progreso que guardar, ni "siguiente
        // capítulo" de series -- el único "siguiente" que existe en vivo es el zapping.
        if (PlayerSource.kindFor(episodeId) == SourceKind.LIVE) {
            loadLive(episodeId.removePrefix(PlayerSource.LIVE_PREFIX))
            return
        }
        viewModelScope.launch {
            // Antes que nada: que el detalle sepa por qué capítulo vas aunque salgas enseguida.
            //
            // Unless it shouldn't be recorded. This is the THIRD path that writes to history, the
            // one that slipped past the other two: it doesn't write position or duration -- the
            // row stays at 0 -- but it DOES write `lastPlayedAt`. Until Task 5 `playback` traveled
            // through cloud sync, so this also sent the record to the cloud and to other devices;
            // without sync the effect stays local, but the row still ends up marked with when this
            // was watched. Found playing for real on the Fire TV on 2026-08-14: the progress, frame
            // and library guards all held, and this row showed up anyway.
            //
            // Acá NO sirve [hayQueAnotarHistorial]: esto corre ANTES de resolver la fuente, cuando
            // `_playlist` todavía es la del episodio anterior (o null), así que preguntarle daría
            // "no sé" → anotar, que es justo lo contrario de lo que hace falta. Lo que sí se sabe a
            // esta altura es el pendiente efímero, que la pantalla dejó antes de navegar.
            //
            // Un canal en vivo de Caracol tampoco se marca: ver [hayQueMarcarEnCurso].
            if (hayQueMarcarEnCurso(episodeId, MagisEphemeral.take(episodeId)?.adulto)) {
                runCatching { repo.markInProgress(episodeId) }
            }
            _error.value = null
            _magisItem.value = null
            // Navegar de un canal en vivo a un episodio VOD sin pasar por otra pantalla (el mismo
            // ViewModel sobrevive, ver el guard de más arriba): sin este reset, `liveItem` seguía
            // publicando el último canal y PlayerScreen (isLive/isLiveExo) lo creía vigente.
            _liveItem.value = null
            errorDeReproduccion = false
            // If it's saved on the device, it wins over any streaming. Goes BEFORE branching by
            // source: no matter where the file came from, it's already here.
            //
            // UNKNOWN stays out on purpose: ids of sources removed from this branch always get
            // the "no longer available" error below, even if an old completed download for that
            // id is still on disk (LocalLibrary.fileFor doesn't filter by source). Playing that
            // old file would be a behavior change, not part of this cleanup.
            val kind = PlayerSource.kindFor(episodeId)
            if (kind != SourceKind.UNKNOWN) {
                val local = localLibrary.fileFor(episodeId)
                if (local != null) { loadLocal(episodeId, local); return@launch }
            }
            // After the download detour, on purpose: a file already on the device carries no
            // trivia (see [TriviaDelPlayer.pideDatos]).
            if (TriviaDelPlayer.pideDatos(episodeId, kind)) cargarTrivia(episodeId)
            Log.w(PLAY, "load() episodeId=$episodeId kind=$kind")
            when (kind) {
                SourceKind.UNKNOWN -> loadUnknownSource(episodeId)
                SourceKind.MAGIS -> loadMagis(episodeId)
                SourceKind.DITU -> loadDitu(episodeId)
                // kindFor() never returns LOCAL: a downloaded file is detected above by
                // localLibrary.fileFor(). The branch exists because the `when` is exhaustive.
                SourceKind.LOCAL -> loadUnknownSource(episodeId)
                // Unreachable: live channels return before this launch (see the guard above).
                SourceKind.LIVE -> Unit
            }
        }
    }

    /** Lo del episodio anterior no puede quedarse en pantalla con el siguiente. */
    private fun apagarTrivia() {
        triviaJob?.cancel()
        _trivia.value = emptyList()
    }

    /**
     * Pide la tanda de datos curiosos, best-effort. Se traga cualquier fallo: sin datos no se dibuja
     * el botón, que es el fallo bueno para algo accesorio. `CancellationException` no se traga:
     * dejaría corriendo una corrutina que su scope ya dio por muerta.
     */
    private fun cargarTrivia(episodeId: String) {
        apagarTrivia()
        val fuenteDeDatos = datosCuriosos ?: return
        triviaJob = viewModelScope.launch {
            val obra = try {
                repo.triviaSubjectFor(episodeId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(PLAY, "trivia: couldn't identify the title: ${e.message}")
                null
            } ?: run {
                // Without this line, trivia would silently turn off: no button and nothing in the
                // log saying why (the item has neither a tmdbId nor a canonical title).
                Log.w(PLAY, "trivia: no titled work for $episodeId → not requested")
                return@launch
            }
            _trivia.value = try {
                fuenteDeDatos.of(obra) { repo.workSheetFor(obra) }
                    .also { Log.w(PLAY, "trivia: ${it.size} facts for ${obra.key}") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(PLAY, "trivia: ${e.javaClass.simpleName}: ${e.message}")
                emptyList()
            }
        }
    }

    // --- Modo vivo (Tarea 14) ---------------------------------------------------------------
    // Isolated from the rest of the file on purpose (see the guard at the start of load()): none
    // of this participates in VOD playlists, casting, local downloads or resume -- concepts that
    // don't exist in live. See LiveController/LiveZapping's KDoc for the full reasoning.

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
        val entrada = LiveZappingSource.list.ifEmpty { listOf(LiveChannel(code, code, 0, null)) }
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
        val canal = zapping?.current ?: return
        _liveCanal.value = canal
        viewModelScope.launch {
            _error.value = null
            errorDeReproduccion = false
            // Fix de revisión (Task 1): igual que loadMagis() descarta la
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
            val url = runCatching { liveController.open(canal.code) }.getOrElse {
                Log.w(PLAY, "abrirCanalActual() failed for ${canal.code}: ${it.message}")
                if (zapping?.current?.code == canal.code) {
                    _error.value = mensajeErrorVivo(hayCuentaDeMagis(), canal.nombre)
                }
                return@launch
            }
            // Zapeos rápidos: si para cuando este abrir() (~3s en el peor caso) vuelve el usuario
            // ya zapeó a OTRO canal, esta respuesta tardía no debe pisar lo que hay en pantalla --
            // mismo patrón (y mismo motivo) que LiveViewModel.cargar() con categoriaActiva, ver su
            // KDoc.
            if (zapping?.current?.code != canal.code) return@launch
            val item = PlayerData(
                episodeId = "${PlayerSource.LIVE_PREFIX}${canal.code}",
                itemId = "${PlayerSource.LIVE_PREFIX}${canal.code}",
                title = canal.nombre,
                subtitle = "",
                mediaUrl = url,
                // Un canal en vivo nunca tiene un mp4 h.264 de respaldo -es un directo, no un
                // archivo-, así que castUrl siempre es null. Eso NO significa que no castee (Tarea
                // 18): PlayerScreen.castRequestFor resuelve la URL alcanzable por LAN del proxy
                // local (LiveHlsProxy.lanUrl) por su cuenta -- ver el KDoc de CastRequestBuilder.
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
            // An adult channel is NOT recorded. And it's solved by NOT WRITING instead of
            // filtering on read: what isn't written can't leak through a screen we forgot about
            // -- "Recents" is drawn in the guide, in the drawer and on the phone. Until Task 5 it
            // also never got uploaded to the cloud, so it wouldn't show up on the account's other
            // devices either; without cloud sync that specific risk is gone, but the risk on THIS
            // device's own screens (above) is still reason enough not to write it. Filtering on
            // read leaves the data sitting there, waiting for the first place that forgets to
            // filter.
            // Por [AdultContent] y no por un `!canal.adulto` suelto: la regla es la misma que
            // la del progreso y la de los frames, y tenerla escrita en un solo lugar es lo que
            // evita que mañana una de las tres se corrija y las otras dos no.
            if (AdultContent.shouldLog(canal.adulto)) {
                runCatching {
                    liveRecentDao.record(LiveRecentEntity(canal.code, canal.nombre, System.currentTimeMillis()))
                }
            }
            precalentarVecinos()
        }
    }

    /** Zapping: siguiente/anterior de la lista con la que se entró. Sin efecto fuera de modo vivo. */
    fun zapSiguiente() { zapping?.next() ?: return; abrirCanalActual() }
    fun zapAnterior() { zapping?.previous() ?: return; abrirCanalActual() }

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
        val canal = zapping?.current ?: return
        if (canal.code != canalDelContador) {
            canalDelContador = canal.code
            reaperturasVivo = 0
        }
        if (reaperturasVivo >= MAX_REAPERTURAS_VIVO) {
            Log.w(PLAY, "live: ${canal.code} didn't come back after $MAX_REAPERTURAS_VIVO reopens → warning")
            _error.value = "Se cortó la señal de ${canal.nombre} y no volvió. " +
                "Puede ser un problema del canal: prueba de nuevo o mira otro."
            return
        }
        if (cortadoEn == 0L) cortadoEn = System.currentTimeMillis()
        reaperturasVivo++
        val espera = ESPERA_REAPERTURA_MS shl (reaperturasVivo - 1)
        Log.w(
            PLAY,
            "live: ${canal.code} cut out → reopening in ${espera}ms " +
                "(attempt $reaperturasVivo/$MAX_REAPERTURAS_VIVO)",
        )
        reabrirJob?.cancel()
        reabrirJob = viewModelScope.launch {
            delay(espera)
            // Zapear durante la espera gana: reabrir acá el canal viejo pisaría el que la persona
            // acaba de elegir.
            if (zapping?.current?.code == canal.code) abrirCanalActual()
        }
    }

    /**
     * El canal se está reproduciendo de verdad: se le repone el presupuesto de reaperturas.
     *
     * Asks for the POSITION and not a boolean because `isPlaying` used to turn true as soon as VLC
     * opened the media, before the first frame: with that, a channel that reopened and died at
     * `pos=0ms` still replenished the budget, the ceiling never ran out, and
     * [reabrirVivoPorCorte]'s warning was unreachable. [MINIMO_VIVO_SANO_MS] is the line between
     * "it recovered" and "it reopened and dropped again".
     */
    fun vivoAndando(posicionMs: Long) {
        if (reaperturasVivo == 0 || posicionMs < MINIMO_VIVO_SANO_MS) return
        val hueco = if (cortadoEn > 0L) System.currentTimeMillis() - cortadoEn else -1L
        Log.w(
            PLAY,
            "live: recovered after ${hueco}ms with no picture and $reaperturasVivo reopen(s) " +
                "(played ${posicionMs}ms) → replenishing the budget",
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
        LiveZappingSource.list = entrada
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
        val vecinos = zapping?.neighbors() ?: return
        precalentarJob = viewModelScope.launch {
            delay(1000)
            vecinos.forEach { vecino -> launch { runCatching { liveController.preheat(vecino.code) } } }
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
     * Plays nothing and reports that the source is gone. Reached for ids whose source was
     * removed from this branch (old library rows with no known prefix).
     */
    private fun loadUnknownSource(episodeId: String) {
        Log.w(PLAY, "loadUnknownSource() episodeId=$episodeId → source not available in this branch")
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
     *
     * [codigo] es el `errorCode` de la `PlaybackException`: con él [CaracolFailure.onPlayback] le
     * dice a la persona qué pasó. Su nombre técnico va al log.
     *
     * [queriaReproducir] pasa a la recarga: si lo que falló estaba en pausa, el reproductor nuevo
     * también (ver [DituReproducible.arrancarSolo]).
     */
    fun onDituExoError(codigo: Int, posicionMs: Long, queriaReproducir: Boolean) {
        val nombre = androidx.media3.common.PlaybackException.getErrorCodeName(codigo)
        val episodio = ditu.pedirRecarga()
        if (episodio == null) {
            Log.w(PLAY, "Caracol: $nombre and no reloads left → notifying the person")
            _error.value = CaracolFailure.onPlayback(codigo, esTelevision)
            return
        }
        Log.w(
            PLAY,
            "Caracol: $nombre → requesting a new URL for $episodio from ${posicionMs}ms " +
                "(wantedToPlay=$queriaReproducir)",
        )
        viewModelScope.launch { loadDitu(episodio, arrancarEnMs = posicionMs, arrancarSolo = queriaReproducir) }
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
        Log.w(PLAY, "live (exo) error for ${zapping?.current?.code}: $message")
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
     * y no el `onIsPlayingChanged` del listener, a propósito: hay tropiezos que el player remonta
     * sin que `isPlaying` llegue a caer, así que colgado de esa transición el cartel se quedaba puesto
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
     * Plays a Magis item.
     *
     * The CDN requires `Content-Auth` and `Content-License`; the stream goes through the local
     * proxy, which can put them on the request to the origin -- libVLC, back when it played this,
     * could only send Referer and User-Agent. The stored [ref] is sent as-is to
     * `MagisResolve.resolveVod`; the app never interprets it.
     */
    private suspend fun loadMagis(episodeId: String) {
        // El contenido de adultos NO tiene fila en la biblioteca —esa es toda la idea, ver
        // [MagisEphemeral]—, así que su `ref` no se puede leer de ahí: viaja por afuera.
        val efimero = MagisEphemeral.take(episodeId)
        val ref = efimero?.ref ?: repo.magisRefForEpisode(episodeId)
        Log.w(PLAY, "loadMagis() episodeId=$episodeId ephemeral=${efimero != null} ref=${ref?.take(12)}…")
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
        Log.w(PLAY, "loadMagis() portal resolve=${msResolve}ms")

        val play = resuelto.getOrNull()
        if (play == null) {
            Log.w(PLAY, "loadMagis() failed: ${resuelto.exceptionOrNull()?.message}")
            _error.value = "No se pudo resolver esta fuente de Magis"
            return
        }

        // Los idiomas que declara el portal son lo ÚNICO que permite elegir subtítulo por idioma en
        // magis: sus pistas embebidas llegan sin idioma en ningún campo (medido en device,
        // `language=null` en `IMedia.Track` y nombre pelado "Track 1", mientras las de audio sí traen
        // spa/eng/jpn). They travel through [webExtras]; PlayerPistas cross-references them with
        // the source via SubtitleDecision.decide.
        Log.w(PLAY, "loadMagis() portal subtitles=${play.subtitles.size} langs=${play.subtitles.map { it.lang }}")

        withContext(Dispatchers.IO) { archiveCacheProxy.start() }
        // Sin fila en la biblioteca no hay cabecera que leer: el título lo trae el propio pendiente,
        // que es lo que la pantalla de categorías tenía en la mano al tocarlo.
        val cabecera = if (efimero != null) null else repo.headerInfo(episodeId)
        // `directo`: el proxy reenvía cada Range al CDN sin cachear. Con la caché (el camino de
        // archive) la descarga de ~1 GB se corta, el proxy borra el archivo y vuelve a empezar en 0
        // mientras el player sigue leyendo por el offset viejo → el TS le llega con huecos, el tiempo salta
        // de a minutos y el video se muere. Sin caché no hay nada que truncar.
        val urlLocal = archiveCacheProxy.proxyUrl(play.url, play.headers, direct = true)
        // THE DURATION IS NO LONGER PROBED BEFORE STARTING. The player reports it on its own once
        // it opens.
        //
        // This used to be the fallback for when magis was demuxed with the native `ts` demuxer,
        // which over HTTP couldn't deduce the duration and left the bar full and stuck at 00:00.
        // Once the demuxer switched to avformat (the player's own duration probe) that fallback
        // stopped being needed: measured on the Fire TV on 2026-08-13, the player reported
        // `dur=7010048ms` for a movie and `dur=3831168ms` for a series episode, both on the first
        // beat and both matching what the probe returned (7009961 and 3831000). The player's own
        // duration is preferred whenever it exists, so whatever came from here was discarded a
        // second later.
        //
        // And it wasn't free: for that same episode, fetching it cost 9.2s of spinner -- two round
        // trips to the CDN before opening the video, against an origin that takes 0.2s to 20s per
        // range -- for a number that would arrive on its own anyway. If the gateway sends it
        // (movies get it for free in the resolve) it's used; otherwise playback starts without it
        // and the player fills it in.
        if (play.durationMs > 0) {
            Log.w(PLAY, "loadMagis() gateway duration=${play.durationMs}ms")
        }
        // El ARRANQUE CALIENTE: lo ÚNICO que se espera antes de abrir el video.
        //
        // Se precalienta en el byte 0, que es donde el player abre SIEMPRE desde que magis dejó de
        // abrir por ventana: reanuda saltando por tiempo, no abriendo el stream más adelante. Sin
        // él, si la primera lectura se demora libVLC se rendía identificando el stream y se quedaba
        // SIN PISTAS para siempre (negro y mudo, con el reloj disparado).
        //
        // La COLA sigue bajándose por detrás —para los sondeos de EOF del player, que quiere el final
        // del archivo apenas abre— pero ya nunca frena el arranque: `waitForTail=false` sin
        // condiciones. Ver ArchiveCacheProxy.preWarm y PrecalentadoNoBloqueaTest.
        val tArranque = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            runCatching {
                archiveCacheProxy.preWarm(
                    play.url, play.headers, fraction = 0f, waitForTail = false,
                    // El contenedor decide si hace falta traer la cola del archivo: un mp4 abre sin
                    // leer el final y bajarla es gasto puro contra el CDN. Si el gateway no lo
                    // manda, la extensión de la URL lo dice igual para magis.
                    container = play.container.ifBlank {
                        com.arkiv.player.playback.VideoContainer.videoExtension(play.url).orEmpty()
                    },
                )
            }
        }
        val msArranque = System.currentTimeMillis() - tArranque
        // Si el gateway mandó la duración, se aprovecha; si no, se arranca sin ella y la completa
        // el player al abrir. Nada de esto pide un solo byte extra.
        val duracion = play.durationMs
        Log.w(PLAY, "loadMagis() hot startup=${msArranque}ms → duration=${duracion}ms")
        val item = PlayerData(
            episodeId = episodeId,
            itemId = episodeId.substringBefore("::"),
            title = cabecera?.itemTitle ?: efimero?.titulo?.takeIf { it.isNotBlank() } ?: "Magis",
            subtitle = cabecera?.episodeLabel.orEmpty(),
            mediaUrl = urlLocal,
            // NOT the url the receiver is given -- `castUrl` is the raw CDN, which answers 401
            // without `Content-Auth`/`Content-License`, and the Cast Default Media Receiver cannot
            // send custom headers. It is kept because it is the only place the TRUE container
            // survives: `MagisResolve` builds it as `_media.ts` or `_media.mp4` from the portal's
            // `videoFormat`, while the proxy url this plays from has no extension at all, so
            // guessing from it always answers mp4. PlayerScreen reads the container from here and
            // casts the proxy over the LAN instead (see `ArchiveCacheProxy.lanUrl`).
            //
            // An older comment here said casting could not work because "el proxy escucha en
            // 127.0.0.1". That was wrong and it cost a full misdiagnosis: `ArchiveCacheProxy.start`
            // opens `ServerSocket(0)` with no bind address, which listens on EVERY interface --
            // only the url string was loopback.
            castUrl = play.url,
            artworkUrl = "",
            openingStartMs = null, openingEndMs = null, endingStartMs = null,
            kind = SourceKind.MAGIS,
            // De acá lo lee [hayQueAnotarHistorial] en cada tick del reproductor. Es la segunda
            // vuelta de llave: la primera es que esto no tenga fila en la biblioteca.
            adulto = efimero?.adulto == true,
        )
        // Acá se forzaba SOFTWARE para el HEVC de magis, dando por hecho que el decodificador por
        // hardware descartaba las pistas (`pistas=v0/a0`). Ese diagnóstico era falso: el que las
        // descartaba era el subtítulo externo (ver PlayerScreen, donde magis no lo adjunta). Sin él,
        // el mismo título arranca con `v2/a3` por hardware y por software. Se deja abrir por
        // hardware —más rápido y sin gastar CPU—; si algún título de verdad falla ahí, hoy no hay
        // rescate "hardware sin imagen → paso a software" para magis (a diferencia de los archivos
        // descargados, ver [DecoderWatchdog]).
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
        // zona mientras el video abre. El player abre siempre en el byte 0 y recién después busca
        // el minuto guardado: medido en el Fire TV, entre una cosa y la otra se bajaban 2,5 MB del
        // principio de la película que después se tiraban, y eso costaba 3,4 s con la imagen
        // congelada en el segundo 0. Ver ArchiveCacheProxy.preWarmSeek.
        //
        // La duración sale del progreso GUARDADO y no del gateway: acá el gateway suele mandar 0
        // (la duración la calcula el player al abrir, que es demasiado tarde para esto), mientras que
        // quien ya vio un pedazo del capítulo tiene la duración anotada de esa vez.
        if (startPos > 0L) {
            val guardado = runCatching { repo.getPlayback(episodeId) }.getOrNull()
            val duracionGuardada = guardado?.durationMs ?: 0L
            if (duracionGuardada > 0L) {
                archiveCacheProxy.preWarmSeek(
                    play.url, play.headers, startPos.toFloat() / duracionGuardada,
                )
            }
        }
        // El arranque caliente ya está en la mano (se pidió arriba, en paralelo con la sonda): la
        // espera del CDN ocurrió ANTES de abrir el video, donde el usuario ve el spinner de
        // siempre, en vez de convertirse en un fallo del que no se vuelve.
        _magisItem.value = item.copy(startPositionMs = startPos)
        // RESUMEN, en una línea y en el orden en que se paga. Lo que falta para el primer frame es
        // lo que tarde el reproductor en abrir, que se mide aparte: la suma de las dos es lo que el
        // usuario ve como spinner.
        Log.w(
            PLAY,
            "loadMagis() ⏱ TOTAL=${System.currentTimeMillis() - t0}ms " +
                "[resolve=${msResolve}ms | startup=${msArranque}ms] startPos=$startPos",
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
     * Un canal en vivo ([DituLive.isLive]) no tiene fila en la biblioteca ni `ref`: se resuelve el
     * canal que dejó la sección de Caracol, con `DituFuente.resolverCanal`, y pasa por las mismas
     * guardas de [EstadoDeDitu] que el VOD. Una recarga ([onDituExoError]) vuelve a entrar por acá
     * con el mismo `episodeId` y resuelve el canal otra vez: por eso [DituLive.take] no lo vacía.
     *
     * [arrancarEnMs] es para las recargas: se retoma donde iba y no desde la posición guardada. Sin
     * él, la misma reanudación que Magis. Un vivo arranca siempre en 0, y con 0 [DituExoPlayer] no
     * hace `seekTo`: queda en la posición por defecto del directo.
     *
     * [arrancarSolo] también es de las recargas: ver [DituReproducible.arrancarSolo].
     */
    private suspend fun loadDitu(episodeId: String, arrancarEnMs: Long? = null, arrancarSolo: Boolean = true) {
        val vivo = DituLive.isLive(episodeId)
        val canal = if (vivo) DituLive.take(episodeId) else null
        val ref = if (vivo) null else repo.magisRefForEpisode(episodeId)
        Log.w(
            PLAY,
            "loadDitu() episodeId=$episodeId ref=${ref?.take(16)}… channel=${canal?.channelId} " +
                "reload=${arrancarEnMs != null}",
        )
        // Lo que sigue toca estado que comparten todas las fuentes: si mientras se leía el ref ya se
        // pidió otro episodio, esto no es de nadie. Ver [EstadoDeDitu].
        if (!ditu.esVigente(episodeId)) return
        val resolver: suspend () -> com.arkiv.player.data.gateway.GatewayPlayable = when {
            canal != null -> suspend { dituFuente.resolveChannel(canal) }
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
            Log.w(PLAY, "loadDitu() discarded: $episodeId is no longer the current request")
            return
        }
        val play = resuelto.getOrNull()
        if (play == null) {
            val falla = resuelto.exceptionOrNull()
            // The detail goes to the log; the person gets what `CaracolFailure` makes of it.
            Log.w(PLAY, "loadDitu() failed: ${falla?.message}", falla)
            _error.value = CaracolFailure.onOpen(falla)
            return
        }
        // La misma reanudación que Magis: [safeStartPosition] sobre el progreso guardado. Un vivo no
        // tiene "dónde ibas", ni siquiera en una recarga.
        val startPos = if (vivo) 0L else arrancarEnMs ?: safeStartPosition(episodeId, SourceKind.DITU)
        Log.w(PLAY, "loadDitu() drm=${play.drmLicenseUrl.isNotBlank()} startPos=$startPos")
        // `publicar` vuelve a mirar si sigue vigente: `safeStartPosition` también suspende.
        // Si está bajado, esto dice de dónde leer. Se busca DESPUÉS de resolver y no antes porque
        // resolver hace falta igual: es lo único que trae el token con el que se pide la licencia.
        val descarga = if (vivo) null else runCatching { localLibrary.caracolDownload(episodeId) }.getOrNull()
        if (descarga != null) {
            Log.w(PLAY, "loadDitu() $episodeId is on the device (${descarga.height}p); media comes off the disk")
        }
        if (!ditu.publicar(
                DituReproducible(episodeId, play, startPos, arrancarSolo = arrancarSolo, descargaLocal = descarga),
            )
        ) {
            Log.w(PLAY, "loadDitu() discarded on publish: $episodeId is no longer the current request")
        }
    }

    /**
     * Validated start position (safe resume): applies the saved position only when resuming makes
     * sense -- more than 10s in, and not near the end. See
     * [com.arkiv.player.playback.ResumePolicy] for why nothing more is needed: torrent (a source
     * removed in this branch's pruning) also required that fraction of the file to already be
     * downloaded, but Magis and Ditu are pure streaming and don't have that problem.
     */
    private suspend fun safeStartPosition(episodeId: String, kind: SourceKind): Long {
        val saved = runCatching { repo.getPlayback(episodeId) }.getOrNull() ?: return 0L
        return com.arkiv.player.playback.ResumePolicy.startPosition(saved.positionMs, saved.durationMs)
            .also { Log.i(PLAY, "resume $episodeId ($kind): saved=${saved.positionMs}ms → starts at ${it}ms") }
    }

    /** La corrección a mano de los tiempos, del capítulo en curso o de la serie. Ver su KDoc. */
    private val markerEditor by lazy {
        com.arkiv.player.data.marcadores.MarkerEditor(dao = repo.skipMarkerDao())
    }

    override fun onCleared() {
        precalentarJob?.cancel()
        super.onCleared()
    }

    /**
     * Marca a mano el fin del opening en [ms].
     *
     * [episodeId] dice a QUÉ se le pone: un capítulo, o `""` = la serie entera (lo que hacía
     * siempre este camino). Poder marcar UN capítulo es lo que hace usable la corrección: con
     * marcadores automáticos por capítulo, un manual de serie le pisa el automático correcto a
     * todos los demás (ver [MarkerEditor]).
     */
    fun setOpeningEnd(ms: Long, episodeId: String = "") = editarMarcador(episodeId) { itemId ->
        markerEditor.setOpeningEnd(itemId, episodeId, ms)
    }

    /** Marca a mano el inicio del ending en [ms]. Ver [setOpeningEnd] para [episodeId]. */
    fun setEndingStart(ms: Long, episodeId: String = "") = editarMarcador(episodeId) { itemId ->
        markerEditor.setEndingStart(itemId, episodeId, ms)
    }

    /** "Esto no tiene intro ni outro". Ver [setOpeningEnd] para [episodeId]. */
    fun clearMarkers(episodeId: String = "") = editarMarcador(episodeId) { itemId ->
        markerEditor.clear(itemId, episodeId)
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
        // Adult content progress is NOT written. "Continue watching" comes straight out of
        // `playback`, and it's drawn on this device's home screen and in the library too -- a row
        // here doesn't stay hidden, even though (unlike until Task 5) it no longer travels through
        // cloud sync to any OTHER device. See [hayQueAnotarHistorial], where the decision and its
        // edge cases live.
        // Magis ExoPlayer: el ítem está en _magisItem, no en _playlist.
        // Caracol cae en la rama de abajo: `loadDitu` deja `_playlist` en null, y con eso
        // [hayQueAnotarHistorial] anota. Salvo un canal en vivo, que no se anota (ver su KDoc):
        // `repo.savePlayback` escribiría la fila aunque no haya episodio en la biblioteca.
        val magisIt = _magisItem.value?.takeIf { it.episodeId == episodeId }
        if (magisIt != null) {
            if (!AdultContent.shouldLog(magisIt.adulto)) return
        } else {
            if (!_playlist.value.hayQueAnotarHistorial(episodeId)) return
        }
        viewModelScope.launch { repo.savePlayback(episodeId, positionMs, durationMs) }
    }

    /**
     * Captura el frame que se está viendo. Best-effort y fuera del camino crítico: si no hay
     * TextureView o el frame no pasa las guardas, no pasa nada.
     *
     * The TextureView comes as a parameter because the views live in `PlayerScreen` (the local one
     * and the in-screen players'); here only `viewModelScope` is needed so the capture doesn't block
     * the composition thread.
     */
    fun capturarFrame(episodeId: String, positionMs: Long, textureView: android.view.TextureView?) {
        // Same guard as progress, and it matters more here: a frame isn't a number, it's an image
        // of what was being watched -- `FrameCapturer.publicar` writes the JPEG straight to local
        // storage. It's the 2026-08-14 leak again, but with a photo. (`episode_frame` used to sync
        // to other devices through PocketBase; that's gone with the rest of cloud sync, but a
        // locally-saved frame of adult content is still exactly the leak this guard exists to stop.)
        val magisIt = _magisItem.value?.takeIf { it.episodeId == episodeId }
        if (magisIt != null) {
            if (!AdultContent.shouldLog(magisIt.adulto)) return
        } else {
            if (!_playlist.value.hayQueAnotarHistorial(episodeId)) return
        }
        viewModelScope.launch { frameCapturer.capture(episodeId, positionMs, textureView) }
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
    }
}
