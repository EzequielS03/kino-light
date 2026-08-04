package com.arkiv.player.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arkiv.player.data.catalog.mirror.MirrorWebPack
import com.arkiv.player.data.catalog.mirror.MirrorWebSource

/**
 * Diálogo de un pack WEB: gemelo de [PackDialog] (torrent) para la serie completa que nuestro
 * backend ya tiene indexada. A diferencia del de torrent no resuelve nada por red — los capítulos
 * vienen en el propio [MirrorWebPack] —, así que abre instantáneo y sin estado de carga.
 */
@Composable
fun WebPackDialog(
    pack: MirrorWebPack,
    defaultTitle: String,
    posterUrl: String,
    onDismiss: () -> Unit,
    onSave: (title: String, episodes: List<MirrorWebSource>) -> Unit,
    onPlayOne: (title: String, episode: MirrorWebSource) -> Unit,
    onDownload: (title: String, episodes: List<MirrorWebSource>) -> Unit,
) {
    var title by remember { mutableStateOf(defaultTitle) }
    // pageUrl como clave: es único por capítulo dentro de un sitio y no depende del orden.
    val selected = remember(pack) { mutableStateListOf<String>().apply { addAll(pack.episodes.map { it.pageUrl }) } }
    fun finalTitle() = title.trim().ifBlank { defaultTitle }

    AlertDialog(
        onDismissRequest = onDismiss,
        // AlertDialog de Material3 solo expone confirmButton/dismissButton (2 slots nombrados), pero
        // acá hacen falta 3 acciones (guardar local, descargar a la NUC, cancelar). En vez de mover
        // "Cancelar" a un ícono (peor descubribilidad) se mete una Column con las 2 acciones positivas
        // dentro del slot confirmButton — apiladas, no en fila, porque un Row con 3 botones de texto
        // (2 acá + "Cancelar" en dismissButton) se pisa/recorta en un diálogo angosto (~360dp). Alineadas
        // a la derecha (Alignment.End) para que calcen con dónde AlertDialog ya pone confirmButton.
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(
                    enabled = selected.isNotEmpty(),
                    onClick = { onDownload(finalTitle(), pack.episodes.filter { it.pageUrl in selected }) },
                ) {
                    Text("Descargar offline (${selected.size})")
                }
                TextButton(
                    enabled = selected.isNotEmpty(),
                    onClick = { onSave(finalTitle(), pack.episodes.filter { it.pageUrl in selected }) },
                ) {
                    Text(
                        if (selected.size == pack.episodeCount) "Guardar todo como serie"
                        else "Guardar seleccionados (${selected.size})",
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        title = { Text("Guardar pack") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                AsyncImage(model = posterUrl, contentDescription = null, modifier = Modifier.height(120.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${pack.episodeCount} capítulos" +
                        (if (pack.seasons.size > 1) "  ·  ${pack.seasons.size} temporadas" else "") +
                        "  ·  ${pack.siteId}",
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(8.dp))

                val allSelected = selected.size == pack.episodeCount && pack.episodes.isNotEmpty()
                fun toggleAll(on: Boolean) {
                    selected.clear()
                    if (on) selected.addAll(pack.episodes.map { it.pageUrl })
                }
                Row(
                    Modifier.fillMaxWidth().clickable { toggleAll(!allSelected) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TriStateCheckbox(
                        state = when {
                            allSelected -> ToggleableState.On
                            selected.isEmpty() -> ToggleableState.Off
                            else -> ToggleableState.Indeterminate
                        },
                        onClick = { toggleAll(!allSelected) },
                    )
                    Text(
                        if (allSelected) "Deseleccionar todo" else "Seleccionar todo",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("${selected.size}/${pack.episodeCount}", style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()

                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    pack.bySeason.forEach { (season, eps) ->
                        // Cabecera por temporada con su propio marcar/desmarcar: un pack real trae
                        // cientos de capítulos y marcarlos de a uno es inviable.
                        item(key = "season-$season") {
                            val urls = eps.map { it.pageUrl }
                            val allOfSeason = urls.all { it in selected }
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        if (allOfSeason) selected.removeAll(urls)
                                        else selected.addAll(urls.filterNot { it in selected })
                                    }
                                    .padding(top = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = allOfSeason,
                                    onCheckedChange = { on ->
                                        if (on) selected.addAll(urls.filterNot { it in selected })
                                        else selected.removeAll(urls)
                                    },
                                )
                                Text("Temporada $season", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.weight(1f))
                                Text("${eps.size}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        items(eps, key = { it.pageUrl }) { ep ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = ep.pageUrl in selected,
                                    onCheckedChange = { on ->
                                        if (on) selected.add(ep.pageUrl) else selected.remove(ep.pageUrl)
                                    },
                                )
                                Column(Modifier.weight(1f).clickable { onPlayOne(finalTitle(), ep) }) {
                                    Text("E${ep.episode}  ${ep.name.ifBlank { "Capítulo ${ep.episode}" }}", maxLines = 2)
                                    val meta = listOfNotNull(
                                        ep.quality.ifBlank { null },
                                        ep.langNorm.ifBlank { null },
                                    ).joinToString("  ·  ")
                                    if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                Text("Tocá un capítulo para reproducirlo ya.", style = MaterialTheme.typography.labelSmall)
            }
        },
    )
}
