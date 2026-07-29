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
import com.arkiv.player.data.catalog.PackFileRow
import com.arkiv.player.data.catalog.PackResolver
import com.arkiv.player.data.catalog.TorrentResult

/** Diálogo de un pack: resuelve sus archivos, permite renombrar el título, elegir capítulos y
 *  guardar (todo/seleccionados) o reproducir uno. */
@Composable
fun PackDialog(
    result: TorrentResult,
    packResolver: PackResolver,
    defaultTitle: String,
    posterUrl: String,
    onDismiss: () -> Unit,
    onSave: (title: String, contents: PackResolver.PackContents, rows: List<PackFileRow>) -> Unit,
    onPlayOne: (title: String, contents: PackResolver.PackContents, row: PackFileRow) -> Unit,
) {
    var contents by remember { mutableStateOf<PackResolver.PackContents?>(null) }
    var failed by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf(defaultTitle) }
    val selected = remember { mutableStateListOf<Int>() } // torrentFileIndex marcados

    LaunchedEffect(result) {
        val c = try {
            packResolver.resolve(result)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (e: Exception) {
            null
        }
        if (c == null) failed = true else { contents = c; selected.addAll(c.rows.map { it.index }) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val c = contents
            if (c != null) TextButton(
                enabled = selected.isNotEmpty(),
                onClick = { onSave(title.trim().ifBlank { defaultTitle }, c, c.rows.filter { it.index in selected }) },
            ) { Text(if (selected.size == c.rows.size) "Guardar todo como serie" else "Guardar seleccionados (${selected.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        title = { Text("Guardar pack") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when {
                    failed -> Text("No se pudo leer el pack (sin seeds ahora).")
                    contents == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp)); Text("Leyendo el pack…")
                    }
                    else -> {
                        val c = contents!!
                        AsyncImage(
                            model = posterUrl, contentDescription = null,
                            modifier = Modifier.height(120.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = title, onValueChange = { title = it },
                            label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        // Seleccionar/deseleccionar todo: un pack de temporada completa puede traer
                        // decenas de capítulos, marcarlos de a uno es inviable.
                        val allSelected = selected.size == c.rows.size && c.rows.isNotEmpty()
                        fun toggleAll(on: Boolean) {
                            selected.clear()
                            if (on) selected.addAll(c.rows.map { it.index })
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
                            Text(
                                "${selected.size}/${c.rows.size}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        HorizontalDivider()
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(c.rows) { row ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = row.index in selected,
                                        onCheckedChange = { on -> if (on) selected.add(row.index) else selected.remove(row.index) },
                                    )
                                    Column(
                                        Modifier.weight(1f).clickable { onPlayOne(title.trim().ifBlank { defaultTitle }, c, row) },
                                    ) {
                                        Text(row.label)
                                        val meta = listOfNotNull(
                                            row.quality.ifBlank { null },
                                            if (row.sizeBytes > 0) "%.0f MB".format(row.sizeBytes / 1_048_576.0) else null,
                                        ).joinToString("  ·  ")
                                        if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        Text("Tocá un capítulo para reproducirlo ya.", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
    )
}
