package com.arkiv.player.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arkiv.player.remote.BarFuente
import com.arkiv.player.remote.TransportCommand
import com.arkiv.player.remote.TvNowPlaying
import com.arkiv.player.remote.TvPlaybackState
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay

/** mm:ss o h:mm:ss según dure. */
internal fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Barra compacta del miniplayer del TV. Dos filas (~80dp) en vez de una: seis controles de 40dp
 * más el título no entran legibles en una sola línea de celu.
 */
@Composable
fun MiniPlayerBar(
    nowPlaying: TvNowPlaying,
    positionMs: Long,
    stale: Boolean,
    fuente: BarFuente,
    /**
     * Si se puede reposicionar. El stream transcodificado hacia el Chromecast sale en vivo (sin
     * duración ni Range, ver [com.arkiv.player.cast.CastTranscoder]): reposicionarlo exige rearmar
     * la petición de cast, y eso solo sabe hacerlo el reproductor (ver Resolución A). En falso, los
     * ±10s quedan deshabilitados en vez de fingir que hacen algo.
     */
    puedeBuscar: Boolean,
    onCommand: (TransportCommand) -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ArkivSurfaceHigh)
            .clickable(onClick = onExpand),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = rememberPoster(nowPlaying),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(Color.DarkGray),
            )
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    nowPlaying.title.ifBlank {
                        if (fuente == BarFuente.CAST) "Reproduciendo en el Chromecast" else "Reproduciendo en la TV"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (nowPlaying.subtitle.isNotBlank()) {
                    Text(
                        nowPlaying.subtitle,
                        color = ArkivTextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                if (stale) "Sin conexión" else "${formatTime(positionMs)} / ${formatTime(nowPlaying.durationMs)}",
                color = ArkivTextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(36.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarButton(Icons.Filled.SkipPrevious, "Capítulo anterior", enabled = nowPlaying.hasPrev) {
                onCommand(TransportCommand.Prev)
            }
            BarButton(Icons.Filled.Replay10, "Atrasar 10 segundos", enabled = puedeBuscar) {
                onCommand(TransportCommand.Seek((positionMs - 10_000).coerceAtLeast(0)))
            }
            PlayPauseButton(nowPlaying.state, onCommand)
            BarButton(Icons.Filled.Forward10, "Adelantar 10 segundos", enabled = puedeBuscar) {
                val tope = if (nowPlaying.durationMs > 0) nowPlaying.durationMs else Long.MAX_VALUE
                onCommand(TransportCommand.Seek((positionMs + 10_000).coerceAtMost(tope)))
            }
            BarButton(Icons.Filled.SkipNext, "Capítulo siguiente", enabled = nowPlaying.hasNext) {
                onCommand(TransportCommand.Next)
            }
            Spacer(Modifier.width(8.dp))
            BarButton(Icons.Filled.Stop, "Parar") {
                onCommand(TransportCommand.Stop)
            }
        }

        LinearProgressIndicator(
            progress = {
                if (nowPlaying.durationMs > 0) {
                    (positionMs.toFloat() / nowPlaying.durationMs).coerceIn(0f, 1f)
                } else {
                    0f
                }
            },
            color = ArkivRed,
            trackColor = Color.DarkGray,
            modifier = Modifier.fillMaxWidth().height(2.dp),
        )
    }
}

@Composable
private fun PlayPauseButton(state: TvPlaybackState, onCommand: (TransportCommand) -> Unit) {
    if (state == TvPlaybackState.BUFFERING) {
        // Sin este estado la barra parecería congelada mientras el torrent bufferea.
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = ArkivRed, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        }
    } else {
        val playing = state == TvPlaybackState.PLAYING
        IconButton(
            onClick = { onCommand(if (playing) TransportCommand.Pause else TransportCommand.Resume) },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pausar" else "Reproducir",
                tint = ArkivRed,
            )
        }
    }
}

@Composable
private fun BarButton(
    icon: ImageVector,
    desc: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // Deshabilitado en vez de oculto: si desapareciera, los otros controles se moverían de lugar
    // al pasar de una serie a una película.
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = desc, tint = if (enabled) Color.White else Color.DarkGray)
    }
}

/** Lo que la UI dibuja: la foto ya pasada por el overlay optimista más la posición extrapolada. */
data class MiniPlayerRender(
    val nowPlaying: TvNowPlaying,
    val positionMs: Long,
    val stale: Boolean,
    val hidden: Boolean,
)

/**
 * Recalcula cuatro veces por segundo DENTRO de un efecto, no en el cuerpo del composable.
 *
 * Importante: `overlay.apply` muta su estado interno al dar por consumido lo pendiente. Llamarlo
 * durante la composición haría que una recomposición extra lo consumiera antes de tiempo y el botón
 * revertiría solo. Acá el bucle lee el overlay en cada tick, así que un comando recién enviado se
 * refleja en ≤250ms.
 */
@Composable
internal fun rememberMiniPlayerRender(
    bar: com.arkiv.player.remote.BarState?,
    overlay: com.arkiv.player.remote.OptimisticOverlay,
    scrubbing: Boolean,
): MiniPlayerRender? {
    var render by remember { mutableStateOf<MiniPlayerRender?>(null) }
    LaunchedEffect(bar, scrubbing) {
        if (bar == null) {
            render = null
            return@LaunchedEffect
        }
        // El reloj de extrapolación trabaja sobre un TvSnapshot: se arma una vez acá a partir del
        // BarState ya normalizado (venga del TV o del Chromecast) y queda fijo hasta que el efecto
        // se reinicie con un BarState distinto.
        val snapshot = com.arkiv.player.remote.TvSnapshot(bar.nowPlaying, bar.receivedAtMs)
        // Base "pinneada" mientras el overlay sostiene un comando: se fija UNA vez, con el instante
        // REAL del comando (no el del tick que lo detecta, hasta 250ms más tarde), y desde ahí
        // extrapola el reloj. Sin esto, pausar deja el tiempo corriendo (la foto cruda sigue
        // diciendo PLAYING) y un seek congela la barra en el destino.
        //
        // Ojo: NO se puede usar "¿la foto ajustada difiere de la cruda?" para decidir si hay un pin
        // vivo. Un resume pedido antes de que un poll confirmara la pausa previa cae sobre una foto
        // cruda que TODAVÍA dice PLAYING (nunca se actualizó a PAUSED) — la foto ajustada coincide
        // por pura casualidad con la cruda aunque el pin siga activo. `overlay.pinnedAtMs()` (no
        // nulo mientras el overlay sostenga algo) es la única señal confiable.
        var pinKey: TvNowPlaying? = null
        var pinBase: com.arkiv.player.remote.TvSnapshot? = null
        while (true) {
            val ahora = System.currentTimeMillis()
            val ajustada = overlay.apply(snapshot.nowPlaying, snapshot.receivedAtMs, ahora)
            val pinnedAt = overlay.pinnedAtMs()
            val base = if (pinnedAt != null) {
                if (pinKey != ajustada) {
                    pinKey = ajustada
                    pinBase = com.arkiv.player.remote.TvSnapshot(
                        ajustada.copy(
                            // Un seek fija su destino; una pausa/play congela o reanuda desde lo que
                            // el usuario está viendo ahora mismo, para no saltar hacia atrás.
                            positionMs = if (ajustada.positionMs != snapshot.nowPlaying.positionMs) {
                                ajustada.positionMs
                            } else {
                                render?.positionMs ?: snapshot.nowPlaying.positionMs
                            },
                        ),
                        pinnedAt,
                    )
                }
                pinBase!!
            } else {
                pinKey = null
                pinBase = null
                snapshot
            }
            render = MiniPlayerRender(
                nowPlaying = ajustada,
                // Con el Chromecast (bar.extrapolar == false) la posición ya viene fresca del
                // receptor en cada tick: congelar la extrapolación es lo que hace falta, igual que
                // si el usuario estuviera arrastrando el slider.
                positionMs = com.arkiv.player.remote.ExtrapolatedClock.positionAt(
                    base, ahora, scrubbing || !bar.extrapolar,
                ),
                // Con el Chromecast el estado es local y `casting` se apaga solo si la sesión cae:
                // la cota de "hace rato sin señal" es del TV y no aplica acá.
                stale = bar.extrapolar && com.arkiv.player.remote.ExtrapolatedClock.isStale(
                    snapshot, ahora, com.arkiv.player.remote.ExtrapolatedClock.WARN_MS,
                ),
                hidden = bar.extrapolar && com.arkiv.player.remote.ExtrapolatedClock.isStale(
                    snapshot, ahora, com.arkiv.player.remote.ExtrapolatedClock.HIDE_MS,
                ),
            )
            delay(250)
        }
    }
    return render
}

/**
 * Carátula a mostrar. El TV ya la resuelve de su biblioteca, pero si llegó vacía se busca en la
 * biblioteca local del celu: es el elemento más visible de la barra y vale la doble red.
 */
@Composable
internal fun rememberPoster(nowPlaying: TvNowPlaying): String? {
    val repo = com.arkiv.player.ui.rememberGraph().repository
    val local by androidx.compose.runtime.produceState<String?>(null, nowPlaying.episodeId) {
        if (nowPlaying.posterUrl.isBlank()) {
            value = runCatching { repo.itemThumbnailForEpisode(nowPlaying.episodeId) }.getOrNull()
        }
    }
    return nowPlaying.posterUrl.ifBlank { local.orEmpty() }.ifBlank { null }
}
