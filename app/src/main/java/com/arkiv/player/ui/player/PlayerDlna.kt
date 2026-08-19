package com.arkiv.player.ui.player

import android.view.ContextThemeWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.arkiv.player.dlna.DlnaController
import com.arkiv.player.dlna.DlnaDevice
import com.arkiv.player.playback.LiveHlsProxy
import com.arkiv.player.playback.SourceKind
import com.arkiv.player.torrent.TorrentEngine
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Todo el DLNA del reproductor: estado, acciones y las tres piezas de interfaz que lo muestran.
 *
 * Vive aparte de [PlayerContent] porque es el único bloque de esa pantalla que no se cruza con
 * ningún otro: nada del transporte, del cast ni del modo vivo lee estas variables. Lo único que
 * el reproductor necesita saber es si hay un renderer activo ([EstadoDlna.activo]), porque eso
 * esconde los controles locales.
 */
@Stable
internal class EstadoDlna(
    private val dlna: DlnaController,
    private val scope: CoroutineScope,
) {
    /** El diálogo de dispositivos está abierto. */
    var pickerAbierto by mutableStateOf(false)
        private set

    /** Corriendo el descubrimiento SSDP (spinner del diálogo). */
    var buscando by mutableStateOf(false)
        private set

    /** Renderers encontrados en la última búsqueda. */
    var dispositivos by mutableStateOf<List<DlnaDevice>>(emptyList())
        private set

    /** Renderer al que le estamos mandando video, o null si reproducimos local. */
    var activo by mutableStateOf<DlnaDevice?>(null)
        private set

    /** El renderer activo está en pausa (lo sabemos porque nosotros se lo pedimos). */
    var pausado by mutableStateOf(false)
        private set

    /**
     * Arranca la búsqueda y abre el picker. Un solo camino para VOD y vivo: ambos bloques de
     * controles (el Row de arriba y el Row del modo vivo) lo disparan igual, la única diferencia
     * entre ambos es el resto del Row que lo rodea (título/marcadores en VOD, badge "EN VIVO" en
     * vivo).
     */
    fun descubrir() {
        pickerAbierto = true
        buscando = true
        dispositivos = emptyList()
        scope.launch {
            val found = withContext(Dispatchers.IO) { dlna.discover() }
            dispositivos = found
            buscando = false
        }
    }

    fun cerrarPicker() {
        pickerAbierto = false
    }

    /** El renderer aceptó el video: de acá en adelante los controles manejan la TV, no el local. */
    fun marcarActivo(device: DlnaDevice) {
        activo = device
        pausado = false
    }

    fun alternarPausa() {
        val dev = activo ?: return
        scope.launch {
            withContext(Dispatchers.IO) { if (pausado) dlna.play(dev) else dlna.pause(dev) }
            pausado = !pausado
        }
    }

    fun detener() {
        val dev = activo ?: return
        scope.launch {
            withContext(Dispatchers.IO) { dlna.stop(dev) }
            activo = null
        }
    }

    /** Corte best-effort al salir de la pantalla: no toca el estado porque ya se está muriendo. */
    fun detenerAlSalir() {
        val dev = activo ?: return
        scope.launch { withContext(Dispatchers.IO) { runCatching { dlna.stop(dev) } } }
    }
}

@Composable
internal fun rememberEstadoDlna(dlna: DlnaController): EstadoDlna {
    val scope = rememberCoroutineScope()
    return remember(dlna, scope) { EstadoDlna(dlna, scope) }
}

/**
 * Le entrega [device] la URL que corresponda a lo que estamos reproduciendo y devuelve si aceptó.
 *
 * Torrent y vivo NO pueden mandar `mediaUrl`: esa es loopback (el server propio escuchando en
 * 127.0.0.1), que desde la TV no resuelve a nada. Los dos tienen que salir por la IP de LAN del
 * server que ya está corriendo acá — el de [TorrentEngine] para torrent, el de [LiveHlsProxy] para
 * vivo. Y `castUrl` tampoco sirve en vivo: nunca hay un mp4 de respaldo para un canal (ver el KDoc
 * de CastRequestBuilder).
 */
internal suspend fun mandarAlRenderer(
    dlna: DlnaController,
    device: DlnaDevice,
    ep: PlayerData?,
    torrentEngine: TorrentEngine,
    liveHlsProxy: LiveHlsProxy,
): Boolean = when (ep?.kind) {
    null -> false

    SourceKind.TORRENT -> {
        val lan = torrentEngine.lanStreamUrl()
        val mime = torrentEngine.streamMime() ?: "video/mp4"
        if (lan != null) withContext(Dispatchers.IO) { dlna.playRawUrl(device, lan, ep.title, mime) } else false
    }

    SourceKind.LIVE -> {
        val lan = torrentEngine.lanIp()?.let { liveHlsProxy.lanUrl(it) }
        if (lan != null) {
            withContext(Dispatchers.IO) {
                dlna.playRawUrl(device, lan, ep.title, "application/vnd.apple.mpegurl")
            }
        } else {
            false
        }
    }

    else -> withContext(Dispatchers.IO) { dlna.setUrlAndPlay(device, ep.castUrl ?: ep.mediaUrl, ep.title) }
}

/** Barra "Reproduciendo en <TV>" con pausa/detener, visible mientras haya un renderer activo. */
@Composable
internal fun BoxScope.BarraDlnaActiva(estado: EstadoDlna) {
    val active = estado.activo ?: return
    Surface(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().systemBarsPadding().padding(16.dp),
        color = ArkivSurface.copy(alpha = 0.96f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Tv, contentDescription = null, tint = ArkivRed)
            Text(
                "Reproduciendo en ${active.friendlyName}",
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = { estado.alternarPausa() }) {
                Text(if (estado.pausado) "Reanudar" else "Pausar")
            }
            TextButton(onClick = { estado.detener() }) { Text("Detener") }
        }
    }
}

/**
 * Diálogo de dispositivos encontrados. No sabe qué se está reproduciendo: elegir un renderer solo
 * avisa por [onElegir], que es quien arma la URL y confirma con [EstadoDlna.marcarActivo].
 */
@Composable
internal fun DialogoDispositivosDlna(
    estado: EstadoDlna,
    onElegir: (DlnaDevice) -> Unit,
) {
    if (!estado.pickerAbierto) return
    AlertDialog(
        onDismissRequest = { estado.cerrarPicker() },
        title = { Text("Reproducir en TV (DLNA)") },
        text = {
            Column {
                when {
                    estado.buscando -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.padding(end = 12.dp).size(20.dp),
                        )
                        Text("Buscando dispositivos…")
                    }

                    estado.dispositivos.isEmpty() -> Text(
                        "No se encontraron dispositivos DLNA. Asegúrate de que la TV esté encendida, " +
                            "en la misma red WiFi y con DLNA habilitado.",
                        color = ArkivTextSecondary,
                    )

                    else -> estado.dispositivos.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    estado.cerrarPicker()
                                    onElegir(device)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Tv, contentDescription = null, tint = ArkivRed)
                            Text(device.friendlyName, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { estado.cerrarPicker() }) { Text("Cerrar") } },
    )
}

/**
 * Botón de DLNA + botón de Chromecast (MediaRouteButton), compartidos por el Row de controles de
 * VOD y el Row propio del modo vivo (Tarea 14/18): ambos ofrecen exactamente los mismos dos
 * botones con el mismo criterio de visibilidad -- lo único que cambia entre ellos es el resto del
 * Row que los rodea (título/marcadores en VOD, badge "EN VIVO" en vivo), así que ESE Row se queda
 * duplicado a propósito pero estos dos botones no.
 */
@Composable
internal fun DlnaCastButtons(
    casting: Boolean,
    castContext: CastContext?,
    onDiscoverDlna: () -> Unit,
) {
    // Casteando no: DLNA es OTRO renderer, y mezclar los dos deja dos TVs reproduciendo lo mismo
    // a la vez. (El botón de Chromecast sí queda visible: es el único camino para cortar la sesión.)
    if (!casting) {
        IconButton(onClick = onDiscoverDlna) {
            Icon(Icons.Default.Tv, contentDescription = "Reproducir en TV (DLNA)", tint = Color.White)
        }
    }
    if (castContext != null) {
        AndroidView(
            modifier = Modifier.padding(horizontal = 8.dp),
            factory = { ctx ->
                val themed = ContextThemeWrapper(ctx, androidx.appcompat.R.style.Theme_AppCompat_DayNight)
                MediaRouteButton(themed).also {
                    CastButtonFactory.setUpMediaRouteButton(ctx.applicationContext, it)
                }
            },
        )
    }
}
