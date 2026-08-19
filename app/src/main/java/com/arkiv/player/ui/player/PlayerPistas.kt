package com.arkiv.player.ui.player

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkiv.player.AppGraph
import com.arkiv.player.data.subtitles.SubtitleTrack
import com.arkiv.player.playback.LangPromotion
import com.arkiv.player.playback.LangTokens
import com.arkiv.player.playback.TrackLang
import com.arkiv.player.playback.VlcPlayer
import com.arkiv.player.ui.settings.etiqueta
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Audio y subtítulos del reproductor: las pistas que trae el archivo (vía la API VLC del player
 * vivo) y las que se bajan de OpenSubtitles, que comparten un solo menú.
 *
 * Sale de [PlayerContent] porque son nueve variables que no lee nadie más de esa pantalla: el
 * único cruce con el resto es el ícono de CC de los controles, que pregunta si hay algún subtítulo
 * puesto. De paso, las tres reglas que sí tenían lógica —cómo se etiqueta una pista sin idioma,
 * en qué orden se listan los resultados y cuándo elegir a mano cambia tu preferencia— quedan como
 * funciones puras acá abajo, fuera del composable y por fin alcanzables desde un test.
 */
@Stable
internal class EstadoDePistas(
    private val vlc: VlcPlayer,
    private val graph: AppGraph,
    private val context: Context,
    private val scope: CoroutineScope,
) {
    /** El menú de audio/subtítulos está abierto. */
    var pickerAbierto by mutableStateOf(false)
        private set

    /** Pistas de subtítulo embebidas en el archivo, como (id, nombre). */
    var spuTracks by mutableStateOf<List<Pair<Int, String>>>(emptyList())
        private set

    /** Pistas de audio embebidas (releases dual: latino / inglés). */
    var audioTracks by mutableStateOf<List<Pair<Int, String>>>(emptyList())
        private set

    var curSpu by mutableIntStateOf(-1)
        private set

    var curAudio by mutableIntStateOf(-1)
        private set

    /** Hay un subtítulo embebido puesto. Lo consulta el ícono de CC de los controles. */
    var subsOn by mutableStateOf(false)
        private set

    /** Resultados de OpenSubtitles para este episodio. */
    var subtitulosOnline by mutableStateOf<List<SubtitleTrack>>(emptyList())
        private set

    /** El subtítulo de OpenSubtitles que está puesto, si el usuario bajó alguno. */
    var subtituloElegido by mutableStateOf<SubtitleTrack?>(null)
        private set

    var buscandoOnline by mutableStateOf(false)
        private set

    /** Hay algún subtítulo puesto, del archivo o bajado. Es lo único que mira el ícono de CC. */
    val haySubtitulo: Boolean get() = subsOn || subtituloElegido != null

    fun abrirPicker() {
        refrescar()
        pickerAbierto = true
    }

    fun cerrarPicker() {
        pickerAbierto = false
    }

    /** Lee las pistas embebidas (audio + subtítulos) del archivo, vía el player vivo. */
    fun refrescar() {
        spuTracks = vlc.vlcSpuTracks()
        audioTracks = vlc.vlcAudioTracks()
        curSpu = vlc.currentSpuTrack()
        curAudio = vlc.currentAudioTrack()
    }

    /**
     * Sincroniza [subsOn] con lo que tiene puesto libVLC. Lo llama el sondeo de reproducción de la
     * pantalla, que es quien sabe cada cuánto conviene mirar.
     */
    fun sincronizarSubsOn() {
        subsOn = vlc.currentSpuTrack() >= 0
    }

    fun elegirAudio(id: Int) {
        vlc.setVlcAudioTrack(id)
        curAudio = id
        promoverIdioma(nombreDe(audioTracks, id) ?: return, audioTracks.nombresReales(), esAudio = true)
    }

    fun elegirSpu(id: Int) {
        vlc.setVlcSpuTrack(id)
        curSpu = id
        if (id < 0) {
            subtituloElegido = null
            return
        }
        promoverIdioma(nombreDe(spuTracks, id) ?: return, spuTracks.nombresReales(), esAudio = false)
    }

    /**
     * Elegir una pista a mano sube ese idioma al tope de la preferencia — pero solo si el archivo
     * tenía más de un idioma (ver LangPromotion: sin alternativa, elegir no expresa preferencia).
     */
    private fun promoverIdioma(pickedName: String, allNames: List<String>, esAudio: Boolean) {
        val prefs = graph.subtitlePrefs.prefs.value
        val nuevo = LangPromotion.promote(
            order = if (esAudio) prefs.audioLangs else prefs.subtitleLangs,
            pickedName = pickedName,
            allNames = allNames,
            classifier = if (esAudio) LangTokens::classify else LangTokens::classifyFileName,
        ) ?: return
        val actualizado = if (esAudio) prefs.copy(audioLangs = nuevo) else prefs.copy(subtitleLangs = nuevo)
        graph.subtitlePrefs.update(actualizado)
        graph.applicationScope.launch {
            runCatching { graph.remoteController.sendSubtitlePrefs(actualizado.toJson()) }
        }
    }

    /** Aplica (o quita) un subtítulo de OpenSubtitles: baja el .srt y lo carga como pista externa. */
    fun aplicarSubtituloOnline(sub: SubtitleTrack?) {
        pickerAbierto = false
        scope.launch {
            val file = if (sub != null) {
                withContext(Dispatchers.IO) {
                    graph.subtitleApi.download(sub.fileId, java.io.File(context.cacheDir, "subs"), sub.language)
                }
            } else {
                null
            }
            if (file != null) {
                vlc.addSubtitleSlave(Uri.fromFile(file))
                subtituloElegido = sub
            } else if (sub == null) {
                // "Ninguno": elección real del usuario, y por eso corta la selección automática.
                vlc.setVlcSpuTrack(-1)
                subtituloElegido = null
            } else {
                // La descarga falló (red). NO se toca la pista: apagarla acá quedaría registrado como
                // una decisión del usuario y dejaría sin auto-selección al resto del ítem — un .srt
                // del torrent que llegue después ya no se prendería. Y por lo mismo tampoco se limpia
                // `subtituloElegido`: en pantalla sigue el subtítulo de antes, así que ponerlo en null
                // dejaba al selector marcando "Ninguno" sobre un subtítulo que se seguía viendo. No
                // cambió nada, así que el estado no cambia; lo único que falta es avisar.
                android.widget.Toast.makeText(
                    context,
                    "No se pudo bajar el subtítulo (revisa la conexión)",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    /**
     * Búsqueda automática en OpenSubtitles para el idioma preferido. Suspende hasta terminar las
     * dos pasadas, así que va colgada de un LaunchedEffect(episodeId) de la pantalla.
     *
     * NO auto-selecciona ninguno: la selección automática (SubtitleDecision) trabaja sobre las
     * pistas que ya trae el archivo, y bajar uno de OpenSubtitles es una acción manual. Quedan
     * listados en el menú CC para cuando el archivo no traiga nada en tu idioma.
     */
    suspend fun buscarOnline(episodeId: String, esTorrent: Boolean) {
        // Un solo origen para lo que se PIDE y para cómo se ORDENA: derivarlos por separado deja que
        // se desincronicen (se pediría un idioma que el orden no conoce, y se iría al fondo).
        val langs = graph.subtitlePrefs.prefs.value.openSubtitlesCodes()
        val ordenIdiomas = langs.split(",")
        val subCtx = graph.repository.subtitleContextForEpisode(episodeId)

        suspend fun buscar(hash: String?) {
            subtitulosOnline = if (subCtx == null && hash == null) {
                emptyList()
            } else {
                val crudos = runCatching {
                    graph.subtitleApi.search(
                        imdbId = subCtx?.imdbId, query = subCtx?.title,
                        season = subCtx?.season, episode = subCtx?.episode, languages = langs,
                        moviehash = hash,
                    )
                }.getOrDefault(emptyList())
                ordenarSubtitulos(crudos, ordenIdiomas)
            }
        }

        buscandoOnline = true
        buscar(null) // 1) por título/imdb, rápido (no espera la descarga)
        buscandoOnline = false

        // 2) TORRENT: el moviehash necesita la cola descargada (puede tardar tras un gate por timeout).
        // Espero a que esté disponible y RE-busco con el hash → sube los subs del release EXACTO al tope.
        if (!esTorrent) return
        repeat(20) {
            val hash = withContext(Dispatchers.IO) {
                runCatching { graph.torrentEngine.servedMovieHash() }.getOrNull()
            }
            if (hash != null) {
                buscar(hash)
                return
            }
            delay(1500)
        }
    }

    private fun nombreDe(tracks: List<Pair<Int, String>>, id: Int): String? =
        tracks.firstOrNull { it.first == id }?.second
}

@Composable
internal fun rememberEstadoDePistas(vlc: VlcPlayer, graph: AppGraph, context: Context): EstadoDePistas {
    val scope = rememberCoroutineScope()
    return remember(vlc, graph, context, scope) { EstadoDePistas(vlc, graph, context, scope) }
}

/** Las pistas reales del contenedor: los ids negativos son las entradas sintéticas del menú. */
internal fun List<Pair<Int, String>>.pistasReales(): List<Pair<Int, String>> = filter { it.first >= 0 }

internal fun List<Pair<Int, String>>.nombresReales(): List<String> = pistasReales().map { it.second }

/**
 * Ordena los resultados de OpenSubtitles: release exacto primero y, dentro de cada nivel, tu idioma
 * preferido arriba.
 *
 * El orden se compara por la SUBETIQUETA BASE: se pide "es" pero las respuestas traen "es-419" o
 * "es-mx" para el latino, que comparado entero no matchearía nunca y mandaría justo al latino al
 * fondo de la lista.
 */
internal fun ordenarSubtitulos(subs: List<SubtitleTrack>, ordenIdiomas: List<String>): List<SubtitleTrack> =
    subs.sortedWith(
        compareByDescending<SubtitleTrack> { it.hashMatch }
            .thenBy { s ->
                val base = s.language.lowercase().substringBefore('-')
                ordenIdiomas.indexOf(base).takeIf { it >= 0 } ?: Int.MAX_VALUE
            },
    )

/**
 * Nombre a mostrar de una pista de subtítulo. El MPEG-TS de magis las entrega sin idioma y libVLC
 * las bautiza "Track 1", "Track 2"…, que no le dice nada a nadie. Cuando la fuente declaró los
 * idiomas (mismo orden que las pistas) se antepone el idioma; si no, se deja el nombre crudo.
 *
 * Misma regla que usa el selector (ver VlcPlayer.clasificarSpuConFuente): cubre las primeras N
 * pistas por id, que son las del contenedor; de ahí en adelante no se adivina. Fuera de magis
 * ([esMagis] en false) la lista de idiomas no describe estas pistas y etiquetarlas con ella sería
 * mentir en el menú.
 */
internal fun etiquetaDeSpu(
    id: Int,
    nombre: String,
    esMagis: Boolean,
    idiomasDeclarados: List<String>,
    spuTracks: List<Pair<Int, String>>,
): String {
    if (id < 0 || !esMagis) return nombre
    val reales = spuTracks.pistasReales().sortedBy { it.first }
    val i = reales.indexOfFirst { it.first == id }
    if (i < 0 || i >= idiomasDeclarados.size) return nombre
    val lang = LangTokens.classifyCode(idiomasDeclarados[i])
    if (lang == TrackLang.UNKNOWN) return nombre
    return "${lang.etiqueta()} · $nombre"
}

/**
 * Menú único de audio y subtítulos: las pistas del contenedor y los resultados de OpenSubtitles.
 *
 * [esMagis] e [idiomasDeclarados] son lo único que el diálogo necesita saber de la fuente, y solo
 * para etiquetar pistas sin idioma (ver [etiquetaDeSpu]).
 */
@Composable
internal fun DialogoDeAudioYSubtitulos(
    estado: EstadoDePistas,
    esMagis: Boolean,
    idiomasDeclarados: List<String>,
) {
    if (!estado.pickerAbierto) return
    // Solo apaga la bandera: del foco se encarga el LaunchedEffect(pickerAbierto) de la pantalla,
    // que es el mismo camino que sigue elegir una pista.
    val cerrar = { estado.cerrarPicker() }
    AlertDialog(
        onDismissRequest = { cerrar() },
        title = { Text("Audio y subtítulos") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                // AUDIO (releases dual: latino / inglés).
                if (estado.audioTracks.pistasReales().size > 1) {
                    TituloDeSeccion("Audio", primera = true)
                    estado.audioTracks.pistasReales().forEach { (id, name) ->
                        TextButton(onClick = { estado.elegirAudio(id) }) {
                            Text(
                                (if (id == estado.curAudio) "✓ " else "") + name,
                                color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // SUBTÍTULOS DEL ARCHIVO (embebidos).
                TituloDeSeccion("Subtítulos del archivo")
                if (estado.spuTracks.pistasReales().isEmpty()) {
                    Text(
                        "Este archivo no trae subtítulos embebidos.",
                        color = ArkivTextSecondary, modifier = Modifier.padding(8.dp),
                    )
                    TextButton(onClick = { estado.elegirSpu(-1) }) {
                        Text((if (estado.curSpu < 0) "✓ " else "") + "Desactivar", color = Color.White)
                    }
                } else {
                    (listOf(-1 to "Desactivar") + estado.spuTracks.pistasReales()).forEach { (id, name) ->
                        TextButton(onClick = { estado.elegirSpu(id) }) {
                            Text(
                                (if (id == estado.curSpu && estado.subtituloElegido == null) "✓ " else "") +
                                    etiquetaDeSpu(id, name, esMagis, idiomasDeclarados, estado.spuTracks),
                                color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // ONLINE (OpenSubtitles): con el login obligatorio SIEMPRE hay sesión de persona (no
                // hay pantalla que componga sin ella), así que esta sección ya no tiene ningún caso
                // real de "no se puede buscar" que ocultar -- se dibuja siempre, directo.
                TituloDeSeccion("Buscar online (OpenSubtitles)")
                when {
                    estado.buscandoOnline -> Row(
                        Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.padding(end = 12.dp).size(20.dp),
                        )
                        Text("Buscando subtítulos…", color = ArkivTextSecondary)
                    }

                    estado.subtitulosOnline.isEmpty() -> Text(
                        "No se encontraron subtítulos en español.",
                        color = ArkivTextSecondary, modifier = Modifier.padding(8.dp),
                    )

                    else -> estado.subtitulosOnline.forEach { s ->
                        TextButton(onClick = { estado.aplicarSubtituloOnline(s) }) {
                            Text(
                                (if (estado.subtituloElegido?.fileId == s.fileId) "✓ " else "↓ ") + s.label,
                                color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { cerrar() }) { Text("Cerrar") } },
    )
}

@Composable
private fun TituloDeSeccion(texto: String, primera: Boolean = false) {
    Text(
        texto,
        style = MaterialTheme.typography.titleSmall,
        color = ArkivRed,
        modifier = Modifier.padding(top = if (primera) 8.dp else 12.dp, bottom = 2.dp),
    )
}
