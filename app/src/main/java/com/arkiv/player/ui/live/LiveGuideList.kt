package com.arkiv.player.ui.live

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveProgram
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * El programa que contiene [instante] (epoch segundos), o null si no hay. La usa también la
 * Tarea 13 (timeline del TV) para resaltar la celda en curso.
 */
fun enCurso(progs: List<LiveProgram>, instante: Long): LiveProgram? =
    progs.firstOrNull { instante >= it.inicio && instante < it.fin }

/** Cuánto lleva corrido un programa, de 0 a 1. La usa también la Tarea 13. */
fun avance(p: LiveProgram, instante: Long): Float {
    val total = (p.fin - p.inicio).toFloat()
    if (total <= 0f) return 0f
    return ((instante - p.inicio).toFloat() / total).coerceIn(0f, 1f)
}

private val horaFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun horaDe(epochSegundos: Long): String =
    Instant.ofEpochSecond(epochSegundos).atZone(ZoneId.systemDefault()).format(horaFormatter)

/**
 * Guía vertical de programación para el celular: un canal por fila, plegada. Al tocarla se
 * despliega mostrando el día completo -- alternativa a la Tarea 13 (timeline canal×hora), que
 * ahí sí tiene sentido porque el TV no pelea con el gesto de scroll vertical natural del teléfono
 * como lo haría un scroll 2D acá.
 *
 * [programacion] es el mismo mapa de [LiveUiState] (Tarea 11): el día completo por canal, ya
 * cacheado ahí para no perderlo al colapsar/expandir filas. El programa en curso de cada fila se
 * DERIVA con [enCurso] en vez de recibir un mapa `ahora` aparte -- son la misma fuente de verdad
 * (el primer programa de la lista del día que contiene el instante actual), y pasar los dos
 * mapas solo abriría la puerta a que se desincronicen.
 *
 * [onPedirEpg] se dispara con los códigos que entran a la ventana visible del `LazyColumn`
 * -detectado por el índice de [rememberLazyListState], no por recomposición- y filtrados contra
 * [programacion]: los que ya tienen día cargado no se vuelven a pedir. [LiveViewModel.pedirEpgDe]
 * ya se blinda solo contra duplicados (ver su KDoc), pero filtrar acá también evita mandarle la
 * lista completa de canales visibles en cada scroll -- solo la diferencia.
 */
@Composable
fun LiveGuideList(
    canales: List<LiveChannel>,
    programacion: Map<String, List<LiveProgram>>,
    onVer: (LiveChannel) -> Unit,
    onPedirEpg: (List<String>) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val listState = rememberLazyListState()

    // rememberUpdatedState para programacion/onPedirEpg (no como key del LaunchedEffect): el
    // efecto se lanza UNA vez por identidad de `canales` y vive escuchando el scroll todo ese
    // tiempo. Si `programacion` fuera key, cada tanda de EPG que llega (ver pedirEpgDe) reiniciaría
    // la colecta -- acá solo necesita ver el mapa más fresco en el momento en que el índice visible
    // cambia, no relanzarse cada vez que ese mapa crece.
    val programacionActual by rememberUpdatedState(programacion)
    val onPedirEpgActual by rememberUpdatedState(onPedirEpg)

    LaunchedEffect(listState, canales) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collect { indices ->
                val faltantes = indices.mapNotNull { canales.getOrNull(it)?.code }
                    .filter { it !in programacionActual }
                if (faltantes.isNotEmpty()) onPedirEpgActual(faltantes)
            }
    }

    LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        items(canales, key = { it.code }) { canal ->
            GuiaCanalRow(canal = canal, programas = programacion[canal.code], onVer = onVer)
        }
    }
}

@Composable
private fun GuiaCanalRow(
    canal: LiveChannel,
    programas: List<LiveProgram>?,
    onVer: (LiveChannel) -> Unit,
) {
    // rememberSaveable (no remember): el LazyColumn con key = canal.code desarma la composición
    // de las filas que salen de la ventana visible -- sin esto, una fila desplegada se replegaba
    // sola al hacer scroll lejos y volver (mismo motivo que DownloadGroupHeader en DownloadsScreen).
    var expandido by rememberSaveable { mutableStateOf(false) }
    val instante = System.currentTimeMillis() / 1000
    val actual = programas?.let { enCurso(it, instante) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expandido = !expandido }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ArkivSurfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (canal.logo != null) {
                    AsyncImage(
                        model = canal.logo,
                        contentDescription = canal.nombre,
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                    )
                } else {
                    Text(
                        text = canal.numero.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = canal.nombre,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Igual que la grilla (ChannelCard): sin EPG todavía se ve como "llegando", nunca
                // como un hueco vacío ni un error -- ver brief.
                Text(
                    text = when {
                        actual != null -> actual.titulo
                        programas != null -> "Sin programación por ahora"
                        else -> "Cargando programación…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = ArkivTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = if (expandido) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expandido) "Contraer" else "Expandir",
                tint = ArkivTextSecondary,
            )
        }

        AnimatedVisibility(
            visible = expandido,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                Button(
                    onClick = { onVer(canal) },
                    colors = ButtonDefaults.buttonColors(containerColor = ArkivRed, contentColor = Color.White),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Ver ahora", modifier = Modifier.padding(start = 6.dp))
                }

                when {
                    programas == null -> Text(
                        "Cargando programación…",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArkivTextSecondary,
                    )
                    programas.isEmpty() -> Text(
                        "Sin programación disponible",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArkivTextSecondary,
                    )
                    else -> programas.forEach { p -> GuiaProgramaRow(p, esActual = p == actual) }
                }
            }
        }
    }
}

/** Una fila `hora — título` de la programación expandida; la del programa en curso, en [ArkivRed]. */
@Composable
private fun GuiaProgramaRow(p: LiveProgram, esActual: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = horaDe(p.inicio),
            style = MaterialTheme.typography.bodySmall,
            color = if (esActual) ArkivRed else ArkivTextSecondary,
            modifier = Modifier.width(44.dp),
        )
        Text(
            text = p.titulo,
            style = MaterialTheme.typography.bodyMedium,
            color = if (esActual) ArkivRed else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (esActual) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
