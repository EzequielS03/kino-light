package com.arkiv.player.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.arkiv.player.AppGraph
import com.arkiv.player.playback.LangPromotion
import com.arkiv.player.playback.LangTokens
import com.arkiv.player.playback.SubtitleDecision
import com.arkiv.player.playback.TrackLang
import com.arkiv.player.playback.TrackSelector
import com.arkiv.player.playback.VlcPlayer
import com.arkiv.player.ui.settings.etiqueta
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay

/**
 * Audio y subtítulos del reproductor: las pistas que trae el archivo, vía la API VLC del player
 * vivo o las que reporta ExoPlayer.
 *
 * Sale de [PlayerContent] porque son varias variables que no lee nadie más de esa pantalla: el
 * único cruce con el resto es el ícono de CC de los controles, que pregunta si hay algún subtítulo
 * puesto. De paso, las dos reglas que sí tenían lógica —cómo se etiqueta una pista sin idioma y
 * cuándo elegir a mano cambia tu preferencia— quedan como funciones puras acá abajo, fuera del
 * composable y por fin alcanzables desde un test.
 */
@Stable
internal class EstadoDePistas(
    private val vlc: VlcPlayer,
    private val graph: AppGraph,
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

    // Referencia al ExoPlayer activo (null → modo VLC). Se actualiza desde PlayerScreen.
    private var exoRef: Player? = null
    // TrackGroups detectados por ExoPlayer para poder seleccionar con setOverrideForType.
    private var exoAudioGroups: List<TrackGroup> = emptyList()
    private var exoSubGroups: List<TrackGroup> = emptyList()

    /** Ya se aplicó el idioma preferido en esta reproducción. Ver [autoElegirIdiomaExo]. */
    private var yaAutoElegiExo = false

    /** Hay un subtítulo embebido puesto. Lo consulta el ícono de CC de los controles. */
    var subsOn by mutableStateOf(false)
        private set

    /** Hay algún subtítulo puesto. Es lo único que mira el ícono de CC de los controles. */
    val haySubtitulo: Boolean get() = subsOn

    fun abrirPicker() {
        refrescar()
        pickerAbierto = true
    }

    fun cerrarPicker() {
        pickerAbierto = false
    }

    /** Vincula el ExoPlayer activo para poder hacer track selection. Null = de vuelta a VLC. */
    fun setExoPlayer(player: Player?) {
        exoRef = player
        // Cada reproducción vuelve a decidir el idioma: lo que se eligió a mano en la anterior no
        // se arrastra a la siguiente (ver [autoElegirIdiomaExo]).
        yaAutoElegiExo = false
        if (player == null) {
            exoAudioGroups = emptyList()
            exoSubGroups = emptyList()
        }
    }

    /**
     * Popula audio y subtítulo desde las pistas que reporta ExoPlayer vía onTracksChanged.
     * Llama a esto desde el callback onTracksChanged de MagisExoPlayer.
     */
    fun actualizarPistasExo(tracks: Tracks) {
        val audioGrupos = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val subGrupos   = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        exoAudioGroups = audioGrupos.map { it.mediaTrackGroup }
        exoSubGroups   = subGrupos.map { it.mediaTrackGroup }

        // Usar el índice como id (para setOverrideForType).
        audioTracks = audioGrupos.mapIndexed { i, group ->
            i to etiquetaDePistaExo(group.getTrackFormat(0), "A${i + 1}")
        }
        spuTracks = subGrupos.mapIndexed { i, group ->
            i to etiquetaDePistaExo(group.getTrackFormat(0), "S${i + 1}")
        }
        curAudio = audioGrupos.indexOfFirst { it.isSelected }.coerceAtLeast(-1)
        curSpu   = subGrupos.indexOfFirst   { it.isSelected }.coerceAtLeast(-1)
        android.util.Log.i("PistasExo", "pistas actualizadas: audio=${audioTracks.size} subs=${spuTracks.size} curAudio=$curAudio curSpu=$curSpu")
        autoElegirIdiomaExo()
    }

    /**
     * Aplica tu idioma preferido de audio y subtítulo, una sola vez por reproducción.
     *
     * Es la misma decisión que toma VlcPlayer —[TrackSelector] para el audio, [SubtitleDecision]
     * para el subtítulo, que los apaga si el audio ya se entiende— pero ExoPlayer no pasaba por
     * ahí: elegía por su cuenta y la preferencia quedaba sin aplicar. Con magis se nota porque sus
     * ficheros traen ocho audios.
     *
     * Solo la primera vez: `onTracksChanged` se dispara también al cambiar de pista, y volver a
     * decidir ahí pisaría lo que acabas de elegir a mano. El flag se limpia en [setExoPlayer], que
     * es por donde entra cada reproducción nueva.
     */
    private fun autoElegirIdiomaExo() {
        if (yaAutoElegiExo) return
        if (audioTracks.isEmpty() && spuTracks.isEmpty()) return
        yaAutoElegiExo = true

        val prefs = graph.subtitlePrefs.prefs.value
        // NO se usan elegirAudio/elegirSpu: esos promueven el idioma elegido en tus preferencias, y
        // eso solo lo puede hacer una elección TUYA. Que el automático se auto-confirmara dejaría
        // la preferencia clavada en lo que hubiera elegido la primera vez.
        //
        // Los nombres ya vienen traducidos ("Español (genérico)", "Japonés"), así que se clasifican
        // como texto libre y no como código ISO.
        TrackSelector.select(audioTracks, prefs.audioLangs, requireChoice = true)
            ?.takeIf { it != curAudio }
            ?.let { elegido ->
                android.util.Log.i("PistasExo", "auto-audio: ${nombreDe(audioTracks, elegido)} (era ${nombreDe(audioTracks, curAudio)})")
                aplicarAudioExo(elegido)
            }

        val spu = SubtitleDecision.decide(
            audioTrackName = nombreDe(audioTracks, curAudio),
            spuTracks = spuTracks,
            prefs = prefs,
            spuClassifier = LangTokens::classify,
        )
        if (spu != curSpu) {
            android.util.Log.i("PistasExo", "auto-subtítulo: ${if (spu < 0) "apagados" else nombreDe(spuTracks, spu)}")
            aplicarSpuExo(spu)
        }
    }

    /** Pone la pista en ExoPlayer y actualiza el estado, sin tocar tus preferencias de idioma. */
    private fun aplicarAudioExo(id: Int) {
        val exo = exoRef ?: return
        exoAudioGroups.getOrNull(id)?.let { group ->
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(TrackSelectionOverride(group, 0))
                .build()
            curAudio = id
        }
    }

    private fun aplicarSpuExo(id: Int) {
        val exo = exoRef ?: return
        if (id < 0) {
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            curSpu = -1
            return
        }
        exoSubGroups.getOrNull(id)?.let { group ->
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group, 0))
                .build()
            curSpu = id
        }
    }

    /**
     * Cómo se llama una pista de ExoPlayer en el menú.
     *
     * Se prueba en tres pasos porque ninguno solo alcanza:
     *  1. La etiqueta que trae el propio archivo, si la trae — nadie describe la pista mejor.
     *  2. [LangTokens], que es el que sabe distinguir latino de castellano. Esa distinción importa
     *     y `Locale` no la hace: para él todo es "español".
     *  3. [java.util.Locale], para lo que a [TrackLang] se le sale del mapa. Ese enum se escribió
     *     para torrents en español —solo contempla latino, castellano, inglés y japonés— y magis
     *     sirve ocho idiomas: sin este paso, portugués, alemán, francés e italiano salían todos
     *     como "Desconocido" en la misma lista.
     *
     * Ojo con el código que manda ExoPlayer: es ISO 639-1, así que el japonés viene como `ja` y la
     * tabla de [LangTokens] solo tiene `jp` y `jpn`. Lo cubre el paso 3.
     */
    private fun etiquetaDePistaExo(fmt: Format, respaldo: String): String {
        fmt.label?.takeIf { it.isNotBlank() }?.let { return it }
        val codigo = fmt.language?.trim()?.takeIf { it.isNotEmpty() } ?: return respaldo
        LangTokens.classifyCode(codigo)
            .takeIf { it != TrackLang.UNKNOWN }
            ?.etiqueta()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        // `forLanguageTag` se traga cualquier cosa y devuelve vacío si no la entiende, así que el
        // respaldo sigue haciendo falta.
        val nombre = java.util.Locale.forLanguageTag(codigo)
            .getDisplayLanguage(java.util.Locale("es"))
        return nombre.takeIf { it.isNotBlank() && !it.equals(codigo, ignoreCase = true) }
            ?.replaceFirstChar { it.uppercase() }
            ?: codigo.uppercase()
    }

    /** Lee las pistas embebidas (audio + subtítulos) del archivo, vía el player vivo. */
    fun refrescar() {
        if (exoRef != null) return  // ExoPlayer: las pistas llegan por actualizarPistasExo, no hay que sondear.
        spuTracks = vlc.vlcSpuTracks()
        audioTracks = vlc.vlcAudioTracks()
        curSpu = vlc.currentSpuTrack()
        curAudio = vlc.currentAudioTrack()
    }

    /**
     * Sincroniza [subsOn] con lo que tiene puesto. Lo llama el sondeo de reproducción de la
     * pantalla, que es quien sabe cada cuánto conviene mirar.
     */
    fun sincronizarSubsOn() {
        subsOn = if (exoRef != null) curSpu >= 0 else vlc.currentSpuTrack() >= 0
    }

    fun elegirAudio(id: Int) {
        val exo = exoRef
        if (exo != null) {
            val group = exoAudioGroups.getOrNull(id)
            if (group != null) {
                exo.trackSelectionParameters = exo.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group, 0))
                    .build()
            }
            curAudio = id
            promoverIdioma(nombreDe(audioTracks, id) ?: return, audioTracks.nombresReales(), esAudio = true)
            return
        }
        vlc.setVlcAudioTrack(id)
        curAudio = id
        promoverIdioma(nombreDe(audioTracks, id) ?: return, audioTracks.nombresReales(), esAudio = true)
    }

    fun elegirSpu(id: Int) {
        val exo = exoRef
        if (exo != null) {
            if (id < 0) {
                exo.trackSelectionParameters = exo.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                curSpu = -1
            } else {
                val group = exoSubGroups.getOrNull(id)
                if (group != null) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(group, 0))
                        .build()
                }
                curSpu = id
                promoverIdioma(nombreDe(spuTracks, id) ?: return, spuTracks.nombresReales(), esAudio = false)
            }
            return
        }
        vlc.setVlcSpuTrack(id)
        curSpu = id
        if (id < 0) return
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
    }

    private fun nombreDe(tracks: List<Pair<Int, String>>, id: Int): String? =
        tracks.firstOrNull { it.first == id }?.second
}

@Composable
internal fun rememberEstadoDePistas(vlc: VlcPlayer, graph: AppGraph): EstadoDePistas {
    return remember(vlc, graph) { EstadoDePistas(vlc, graph) }
}

/** Las pistas reales del contenedor: los ids negativos son las entradas sintéticas del menú. */
internal fun List<Pair<Int, String>>.pistasReales(): List<Pair<Int, String>> = filter { it.first >= 0 }

internal fun List<Pair<Int, String>>.nombresReales(): List<String> = pistasReales().map { it.second }

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
 * Menú de audio y subtítulos: las pistas que trae el contenedor (archivo o stream).
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
                                (if (id == estado.curSpu) "✓ " else "") +
                                    etiquetaDeSpu(id, name, esMagis, idiomasDeclarados, estado.spuTracks),
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
