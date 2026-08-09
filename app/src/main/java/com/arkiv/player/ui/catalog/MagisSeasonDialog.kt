package com.arkiv.player.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkiv.player.data.gateway.ArkivApiClient
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * Ventana de una temporada de Magis.
 *
 * Existe porque un resultado de serie del portal **es una temporada entera**, no un capítulo:
 * "Breaking Bad T5" son 16 capítulos bajo un solo ítem. Sin esta pantalla, tocar ese resultado
 * reproducía el capítulo 1 en silencio, sin forma de elegir.
 *
 * Los capítulos se piden al abrir (`/v1/episodes`): no vienen en el resultado de búsqueda porque
 * el portal los entrega en otra llamada, y pedirlos para las 20 series de una búsqueda gastaría el
 * rate-limit del portal en listas que nadie va a mirar.
 */
@Composable
fun MagisSeasonDialog(
    season: GatewayResult,
    client: ArkivApiClient,
    onDismiss: () -> Unit,
    onPlay: (GatewayEpisode) -> Unit,
) {
    var capitulos by remember(season.ref) { mutableStateOf<List<GatewayEpisode>?>(null) }
    var error by remember(season.ref) { mutableStateOf<String?>(null) }

    LaunchedEffect(season.ref) {
        runCatching { client.episodes(season.ref) }
            .onSuccess { capitulos = it }
            .onFailure { error = "No se pudieron cargar los capítulos." }
    }

    val esperados = season.extra["episode_count"]?.toIntOrNull() ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        title = {
            Column {
                Text(season.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
                    MetaChip("Magis", ArkivMagisBlue)
                    if (season.year.isNotBlank()) MetaChip(season.year)
                    // El conteo del portal se muestra aunque la lista aún no llegue: da idea del
                    // tamaño de la temporada mientras carga.
                    if (esperados > 0) MetaChip("$esperados capítulos")
                }
            }
        },
        text = {
            when {
                error != null -> Text(error!!, color = ArkivTextSecondary)

                capitulos == null -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = ArkivMagisBlue)
                    Spacer(Modifier.size(12.dp))
                    Text("Cargando capítulos…", color = ArkivTextSecondary)
                }

                capitulos!!.isEmpty() -> Text(
                    "Esta temporada no trae capítulos.",
                    color = ArkivTextSecondary,
                )

                else -> LazyColumn(
                    Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(capitulos!!, key = { it.ref }) { cap ->
                        EpisodeRow(cap) { onPlay(cap) }
                    }
                }
            }
        },
    )
}

/** Una fila de capítulo: número, nombre y play. */
@Composable
private fun EpisodeRow(cap: GatewayEpisode, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(ArkivSurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Text(
                cap.number.toString(),
                color = ArkivMagisBlue,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            // El portal a veces repite el nombre de la temporada en el capítulo
            // ("Breaking Bad T5_8"): cuando el título no aporta, se muestra el número.
            cap.title.takeIf { it.isNotBlank() && it != cap.number.toString() } ?: "Capítulo ${cap.number}",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(20.dp).weight(1f),
        )
        Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir", tint = ArkivMagisBlue)
    }
}
