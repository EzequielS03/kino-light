package com.arkiv.player.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.arkiv.player.remote.BarFuente
import com.arkiv.player.remote.OptimisticOverlay
import com.arkiv.player.remote.TransportCommand
import com.arkiv.player.remote.TvPlaybackState
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.launch

/**
 * Control completo de lo que se reproduce (TV o Chromecast): slider arrastrable, los mismos cinco
 * controles en grande y "parar" debajo en su propia línea (los seis no entran en una sola fila en
 * un ancho de celu, ver el comentario junto al Spacer que lo separa).
 */
@Composable
fun NowPlayingScreen(onBack: () -> Unit, onOpenRemote: () -> Unit, onAbrirEpisodio: (String) -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val barState by graph.nowPlayingCoordinator.state.collectAsStateWithLifecycle()
    val overlay = remember { OptimisticOverlay() }
    // Un pin (pausa/seek optimista) pertenece a la fuente que estaba en pantalla cuando se mandó el
    // comando: no significa nada en la otra. Con la regla de "gana el último" el flip entre TV y
    // Chromecast es más frecuente y ahora bidireccional, así que sin este clear un pin pedido a una
    // fuente podía quedar pintado encima de la foto de la OTRA cuando la barra cambiaba de dueño.
    LaunchedEffect(barState?.fuente) { overlay.clear() }

    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }

    val render = rememberMiniPlayerRender(barState, overlay, scrubbing)
    // Cerrar cuando DEJA de reproducir (barState null, sea TV o Chromecast) y también cuando lleva
    // demasiado tiempo sin dar señales (`render.hidden`, el mismo umbral con el que la barra se
    // oculta). Con Chromecast `render.hidden` siempre da false (no aplica la cota de rancio, ver
    // rememberMiniPlayerRender), así que ahí el cierre depende solo de `barState == null`. Sin lo
    // segundo, un TV que se apaga a mitad de capítulo deja esta pantalla extrapolando el slider hasta
    // el final, con controles que parecen vivos e indefinidamente: preferimos que desaparezca antes
    // que mienta sobre lo que pasa en el TV.
    //
    // OJO: no sirve `render == null` para esto — rememberMiniPlayerRender arranca en null y solo se
    // puebla en su primer tick, así que cerraría la pantalla en el mismo instante en que se abre.
    val muerto = render?.hidden == true
    LaunchedEffect(barState == null, muerto) { if (barState == null || muerto) onBack() }
    if (render == null) return

    val visible = render.nowPlaying
    val positionMs = if (scrubbing) scrubValue.toLong() else render.positionMs

    // El stream transcodificado hacia el Chromecast sale en vivo (sin duración ni Range):
    // reposicionarlo exige rearmar la petición de cast, algo que solo sabe hacer el reproductor
    // (ver Resolución A). `activeUrl` es un `var` plano, no estado de Compose, pero acá alcanza:
    // mientras se castea esta pantalla recompone cada 250ms con un BarState fresco
    // (rememberMiniPlayerRender), así que el valor se refresca solo con eso.
    val puedeBuscar = barState?.fuente != BarFuente.CAST || graph.castTranscoder.activeUrl == null

    fun enviar(cmd: TransportCommand) {
        // El overlay optimista es solo para el TV: con el Chromecast el próximo tick (≤250ms) ya
        // refleja el cambio real, y fijar un estado encima solo agrega parpadeo.
        if (barState?.fuente == BarFuente.TV) {
            val t = System.currentTimeMillis()
            when (cmd) {
                TransportCommand.Pause -> overlay.expectState(TvPlaybackState.PAUSED, t)
                TransportCommand.Resume -> overlay.expectState(TvPlaybackState.PLAYING, t)
                is TransportCommand.Seek -> overlay.expectPosition(cmd.positionMs, t)
                else -> overlay.clear()
            }
        } else {
            overlay.clear()
        }
        scope.launch { enviarComandoDeBarra(graph, barState, cmd, context, onAbrirEpisodio) }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(ArkivBlack).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
            }
            // Mismo aviso que la barra: si la foto lleva rato sin refrescarse, decirlo. Lo que se ve
            // (slider, tiempos, estado) es extrapolación local, no lo que el TV está haciendo.
            // `render.stale` siempre da false casteando (bar.extrapolar es false en esa rama), así
            // que esa rama es inalcanzable con Chromecast — nombrarlo ahí es solo cuestión de texto.
            Text(
                when {
                    render.stale -> "Sin conexión con la TV"
                    barState?.fuente == BarFuente.CAST -> "Reproduciendo en el Chromecast"
                    else -> "Reproduciendo en la TV"
                },
                color = if (render.stale) ArkivRed else ArkivTextSecondary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        Spacer(Modifier.height(24.dp))

        AsyncImage(
            model = rememberPoster(visible),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.DarkGray),
        )

        Spacer(Modifier.height(24.dp))

        Text(
            visible.title.ifBlank { "Reproduciendo" },
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (visible.subtitle.isNotBlank()) {
            Text(
                visible.subtitle,
                color = ArkivTextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(28.dp))

        // Rango real del slider, calculado UNA vez: el valor se acota a este mismo rango para que
        // nunca puedan discrepar. Con duración desconocida (torrent recién abierto) el reloj
        // extrapolado NO tapa la posición — sigue creciendo sin tope — así que sin este coerceIn el
        // valor quedaría fuera de 0f..1f.
        val sliderRange = 0f..(if (visible.durationMs > 0) visible.durationMs.toFloat() else 1f)
        Slider(
            value = positionMs.toFloat().coerceIn(sliderRange),
            onValueChange = { v -> scrubbing = true; scrubValue = v },
            onValueChangeFinished = {
                scrubbing = false
                enviar(TransportCommand.Seek(scrubValue.toLong()))
            },
            valueRange = sliderRange,
            enabled = visible.durationMs > 0 && puedeBuscar,
            colors = SliderDefaults.colors(
                thumbColor = ArkivRed,
                activeTrackColor = ArkivRed,
                inactiveTrackColor = Color.DarkGray,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatTime(positionMs), color = ArkivTextSecondary, style = MaterialTheme.typography.bodySmall)
            Text(formatTime(visible.durationMs), color = ArkivTextSecondary, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(20.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BigButton(Icons.Filled.SkipPrevious, "Capítulo anterior", enabled = visible.hasPrev) {
                enviar(TransportCommand.Prev)
            }
            BigButton(Icons.Filled.Replay10, "Atrasar 10 segundos", enabled = puedeBuscar) {
                enviar(TransportCommand.Seek((positionMs - 10_000).coerceAtLeast(0)))
            }
            Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                if (visible.state == TvPlaybackState.BUFFERING) {
                    CircularProgressIndicator(color = ArkivRed, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                } else {
                    val playing = visible.state == TvPlaybackState.PLAYING
                    IconButton(
                        onClick = { enviar(if (playing) TransportCommand.Pause else TransportCommand.Resume) },
                        modifier = Modifier.size(64.dp),
                    ) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "Pausar" else "Reproducir",
                            tint = ArkivRed,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
            }
            BigButton(Icons.Filled.Forward10, "Adelantar 10 segundos", enabled = puedeBuscar) {
                val tope = if (visible.durationMs > 0) visible.durationMs else Long.MAX_VALUE
                enviar(TransportCommand.Seek((positionMs + 10_000).coerceAtMost(tope)))
            }
            BigButton(Icons.Filled.SkipNext, "Capítulo siguiente", enabled = visible.hasNext) {
                enviar(TransportCommand.Next)
            }
        }

        // En su propia línea y no como sexto control de la fila de arriba: esa fila mide
        // 5×56dp + 64dp + 4×12dp = 424dp de contenido, más ancha que cualquier celu (360-430dp) —
        // un sexto botón ahí medía 0dp (Compose clampea el sobrante), invisible e imposible de
        // tocar. Acá abajo, con más espacio (la barra compacta ya lo tiene inline, ver MiniPlayerBar).
        Spacer(Modifier.height(20.dp))
        BigButton(Icons.Filled.Stop, "Parar") { enviar(TransportCommand.Stop) }

        Spacer(Modifier.weight(1f))

        TextButton(onClick = onOpenRemote) {
            Icon(Icons.Filled.SettingsRemote, contentDescription = null, tint = ArkivTextSecondary)
            Text("  Control remoto", color = ArkivTextSecondary)
        }
    }
}

@Composable
private fun BigButton(icon: ImageVector, desc: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(56.dp)) {
        Icon(
            icon,
            contentDescription = desc,
            tint = if (enabled) Color.White else Color.DarkGray,
            modifier = Modifier.size(30.dp),
        )
    }
}
