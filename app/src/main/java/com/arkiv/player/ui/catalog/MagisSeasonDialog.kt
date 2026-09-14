package com.arkiv.player.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arkiv.player.data.gateway.ContentSource
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.GatewaySerie
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * Ventana de una temporada de Magis.
 *
 * También abre las series de Caracol, con su [etiqueta] y su [acento]. La ventana no guarda nada:
 * qué pasa al tocar un capítulo lo decide quien la abre, en [onPlay] y [onSave].
 *
 * Existe porque un resultado de serie del portal **es una temporada entera**, no un capítulo:
 * "Breaking Bad T5" son 16 capítulos bajo un solo ítem. Sin esta pantalla, tocar ese resultado
 * reproducía el capítulo 1 en silencio, sin forma de elegir.
 *
 * Los capítulos se piden al abrir (`MagisCatalog.detail`): no vienen en el resultado de búsqueda
 * porque el portal los entrega en otra llamada, y pedirlos para las 20 series de una búsqueda
 * gastaría el rate-limit del portal en listas que nadie va a mirar.
 */
@Composable
fun MagisSeasonDialog(
    season: GatewayResult,
    client: ContentSource,
    onDismiss: () -> Unit,
    onPlay: (List<GatewayEpisode>, GatewayEpisode, GatewaySerie?) -> Unit,
    // La [GatewaySerie] viaja también en el guardado, no solo en el play: guardar escribe la fila
    // del episodio entera (REPLACE), así que sin ella los capítulos marcados perderían la temporada
    // que el play ya había guardado bien. Ver `SearchPlayback.magisEpisodeIdFor`.
    // Null = descarga deshabilitada.
    //
    // Van las DOS listas: los capítulos elegidos y la temporada entera que la ventana ya cargó.
    // Magis solo necesita los elegidos, pero Caracol guarda la serie completa para poder guardar
    // uno (`ArkivRepository.addDituSeason`), y sin fila en `episodes` la descarga después no
    // encuentra el `ref`.
    onSave: ((todos: List<GatewayEpisode>, elegidos: List<GatewayEpisode>, GatewaySerie?) -> Unit)? = null,
    // El nombre y el color de la fuente. La ventana también abre las series de Caracol.
    etiqueta: String = "Magis",
    acento: Color = ArkivMagisBlue,
) {
    var capitulos by remember(season.ref) { mutableStateOf<List<GatewayEpisode>?>(null) }
    // El bloque `series` de la misma respuesta: de ahí sale el `tmdbId` que necesita
    // `SearchPlayback.playMagisSeason` para guardarlo en el ítem, sin pedirlo de nuevo al tocar un
    // capítulo (ver su KDoc).
    var serie by remember(season.ref) { mutableStateOf<GatewaySerie?>(null) }
    var error by remember(season.ref) { mutableStateOf<String?>(null) }
    // Selección para guardar. Arranca vacía: el gesto principal de esta ventana es reproducir, y
    // marcar los 16 capítulos por defecto invitaría a bajar una temporada entera sin querer.
    val marcados = remember(season.ref) { mutableStateListOf<Int>() }
    val puedeGuardar = onSave != null
    // El capítulo que se tocó y todavía no decidió si se ve o se baja. Ver el diálogo del final.
    var porElegir by remember(season.ref) { mutableStateOf<GatewayEpisode?>(null) }

    LaunchedEffect(season.ref) {
        // What the dialog was opened with. `program_type` is what decides this is a series (see
        // MAGIS_SERIES): if the portal tagged something as a series that has no season,
        // MagisCatalog.detail responds 422 and from the UI it looks just like a network drop.
        android.util.Log.w(
            "ArkivGw",
            "season: requesting chapters title=${season.title} type=${season.extra["program_type"]} " +
                "expected=${season.extra["episode_count"]} kind=${season.kind} ref=${season.ref.take(24)}…",
        )
        try {
            val (caps, s) = client.episodesWithSeries(season.ref)
            android.util.Log.w("ArkivGw", "season: ok caps=${caps.size}")
            capitulos = caps
            serie = s
        } catch (e: kotlinx.coroutines.CancellationException) {
            android.util.Log.w("ArkivGw", "season: cancelled (CancellationException) ${e.message}")
            throw e
        } catch (e: Throwable) {
            android.util.Log.w("ArkivGw", "season: failed ${e.javaClass.simpleName}: ${e.message}", e)
            error = "No se pudieron cargar los capítulos."
        }
    }

    val esperados = season.extra["episode_count"]?.toIntOrNull() ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (puedeGuardar && marcados.isNotEmpty()) {
                    val caps = capitulos.orEmpty()
                    val elegidos = caps.filter { it.number in marcados }
                    TextButton(onClick = { onSave!!(caps, elegidos, serie); onDismiss() }) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = acento)
                        Spacer(Modifier.size(6.dp))
                        Text("Guardar ${elegidos.size}", color = acento)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        },
        dismissButton = {
            val caps = capitulos.orEmpty()
            if (puedeGuardar && caps.isNotEmpty()) {
                TextButton(onClick = {
                    // Alterna entre "toda la temporada" y "ninguno": el caso frecuente es querer
                    // la temporada completa, y marcar 16 casillas a mano sería absurdo.
                    if (marcados.size == caps.size) marcados.clear()
                    else { marcados.clear(); marcados.addAll(caps.map { it.number }) }
                }) {
                    Text(
                        if (marcados.size == caps.size) "Ninguno" else "Toda la temporada",
                        color = ArkivTextSecondary,
                    )
                }
            }
        },
        title = {
            Column {
                Text(season.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
                    MetaChip(etiqueta, acento)
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
                    CircularProgressIndicator(Modifier.size(20.dp), color = acento)
                    Spacer(Modifier.size(12.dp))
                    Text("Cargando capítulos…", color = ArkivTextSecondary)
                }

                capitulos!!.isEmpty() -> Text(
                    "Esta temporada no trae capítulos.",
                    color = ArkivTextSecondary,
                )

                else -> {
                    // Con varias temporadas (una serie de Caracol), por temporada y número, y cada
                    // fila dice la suya. Sin temporada —Magis— queda como llegó. Ver
                    // [CapitulosPorTemporada].
                    val enOrden = CapitulosPorTemporada.ordenar(capitulos!!)
                    val variasTemporadas = CapitulosPorTemporada.variasTemporadas(capitulos!!)
                    LazyColumn(
                        Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(enOrden, key = { it.ref }) { cap ->
                            EpisodeRow(
                                cap = cap,
                                etiqueta = CapitulosPorTemporada.etiqueta(cap, variasTemporadas),
                                marcado = cap.number in marcados,
                                mostrarCasilla = puedeGuardar,
                                acento = acento,
                                onMarcar = {
                                    if (cap.number in marcados) marcados.remove(cap.number)
                                    else marcados.add(cap.number)
                                },
                                onPlay = {
                                    // Donde se puede bajar, tocar un capítulo PREGUNTA; donde no
                                    // (Caracol es Widevine, ver `DownloadSource`), ver es el único
                                    // gesto posible y un diálogo de una sola opción solo estorba.
                                    if (puedeGuardar) porElegir = cap
                                    else onPlay(capitulos!!, cap, serie)
                                },
                            )
                        }
                    }
                }
            }
        },
    )

    // Ver o bajar ESTE capítulo. Es el gemelo del diálogo que ya tenían las películas
    // (`MagisTapDecision.ShowMovieDialog`): hasta ahora bajar un capítulo solo se podía marcando su
    // casilla, que es un gesto para varios capítulos a la vez y que nadie encuentra cuando quiere
    // uno. Va como un Dialog HERMANO y no dentro del `text` del de arriba: uno anidado en el
    // contenido del otro hereda su ancho y su scroll.
    porElegir?.let { cap ->
        val caps = capitulos.orEmpty()
        AlertDialog(
            onDismissRequest = { porElegir = null },
            title = { Text(nombreDeCapitulo(cap), color = Color.White, fontWeight = FontWeight.SemiBold) },
            confirmButton = {
                TextButton(onClick = { porElegir = null; onPlay(caps, cap, serie) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = acento)
                    Spacer(Modifier.size(6.dp))
                    Text("Ver", color = acento)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    porElegir = null
                    onSave!!(caps, listOf(cap), serie)
                    // Se cierra la temporada, igual que al guardar varios: quien encola es la
                    // pantalla de atrás y ahí es donde se ve si entró en la cola o ya estaba.
                    onDismiss()
                }) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = ArkivTextSecondary)
                    Spacer(Modifier.size(6.dp))
                    Text("Descargar", color = ArkivTextSecondary)
                }
            },
        )
    }
}

/**
 * Cómo se llama un capítulo en pantalla.
 *
 * El número del portal MANDA: identifica el capítulo que se va a reproducir, y si el cruce con
 * TMDB quedara corrido para esta temporada, sigue siendo el dato cierto. El nombre va al lado,
 * nunca en su lugar. Prioridad: título de TMDB (el real) -> título del portal (salvo que solo
 * repita el número) -> "Capítulo N" como último respaldo.
 */
private fun nombreDeCapitulo(cap: GatewayEpisode): String =
    cap.tmdbTitle?.takeIf { it.isNotBlank() }
        ?: cap.title.takeIf { it.isNotBlank() && it != cap.number.toString() }
        ?: "Capítulo ${cap.number}"

/** Una fila de capítulo: casilla para guardar, número (o temporada y número, ver
 *  [CapitulosPorTemporada]), nombre y play. */
@Composable
private fun EpisodeRow(
    cap: GatewayEpisode,
    etiqueta: String = cap.number.toString(),
    marcado: Boolean,
    mostrarCasilla: Boolean = true,
    acento: Color = ArkivMagisBlue,
    onMarcar: () -> Unit,
    onPlay: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(ArkivSurfaceHigh)
            // Tocar la fila REPRODUCE; la casilla es un objetivo aparte. Al revés, marcar para
            // guardar se llevaría por delante el gesto más común.
            .clickable(onClick = onPlay)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (mostrarCasilla) {
            Box(
                Modifier.size(28.dp).clickable(onClick = onMarcar),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (marcado) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = if (marcado) "Quitar de la descarga" else "Guardar este capítulo",
                    tint = if (marcado) acento else ArkivTextSecondary,
                )
            }
        }
        // "T2 · E1" no cabe en el cuadro de 28dp: con temporada se ensancha. El número pelado —el de
        // Magis, siempre— queda en el cuadro de siempre.
        val conTemporada = etiqueta != cap.number.toString()
        Box(
            if (conTemporada) Modifier.height(28.dp).widthIn(min = 28.dp) else Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                etiqueta,
                color = acento,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (!cap.still.isNullOrBlank()) {
            Box(
                Modifier.height(40.dp).width(40.dp * 16f / 9f)
                    .clip(RoundedCornerShape(4.dp)).background(Color.Black),
            ) {
                AsyncImage(
                    model = cap.still,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            nombreDeCapitulo(cap),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(20.dp).weight(1f).padding(start = 8.dp),
        )
        Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir", tint = acento)
    }
}
