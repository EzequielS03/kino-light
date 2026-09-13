package com.arkiv.player.ui.tv.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.data.LibraryGroup
import com.arkiv.player.data.biblioteca.LibraryFilter
import com.arkiv.player.data.biblioteca.LibrarySection
import com.arkiv.player.data.biblioteca.LibraryWatched
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.tv.arkivTvButtonBorder
import com.arkiv.player.ui.tv.arkivTvButtonColors
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextPrimary
import com.arkiv.player.ui.theme.ArkivTextSecondary
import com.arkiv.player.ui.tv.TvPosterCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CARD_HEIGHT = 200.dp

/**
 * Aire contra los bordes de la pantalla, compartido por todas las secciones.
 *
 * No es gusto: un TV recorta el borde de la imagen (overscan) y cuánto recorta depende del aparato,
 * así que lo que quede a menos de ~5% del borde puede no verse. Con los 24 dp que tenía el menú, el
 * texto quedaba pegado al canto. Estos valores dejan el contenido dentro de la zona segura y de paso
 * se lee mejor de lejos.
 */
internal val SAFE_H = 44.dp
internal val SAFE_V = 44.dp

/**
 * "Mi biblioteca" del TV: lo guardado, lo ya visto y las descargas al dispositivo.
 *
 * Existe porque el home no alcanzaba: su zona de filas mide exactamente dos filas, así que con algo
 * en "Continuar viendo" la fila de Películas nacía fuera de pantalla y no había forma razonable de
 * llegar a lo guardado. Acá el contenido propio tiene su lugar y no compite con ~40 filas de
 * descubrimiento.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvLibraryScreen(
    onOpenItem: (String) -> Unit,
    onPlayEpisode: (String) -> Unit,
    onBack: () -> Unit,
) {
    val graph = rememberGraph()
    val vm: TvLibraryViewModel = viewModel(
        factory = viewModelFactory { initializer { TvLibraryViewModel(graph.repository) } },
    )
    val grupos by vm.grupos.collectAsStateWithLifecycle()
    val vistos by vm.vistos.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var seccion by remember { mutableStateOf(LibrarySection.ALL_SAVED) }
    var menuDe by remember { mutableStateOf<LibraryGroup?>(null) }

    BackHandler(enabled = menuDe == null) { onBack() }

    // El foco arranca en el menú. Mismo patrón de reintento que el home: a los 150 ms la fila puede
    // no estar compuesta todavía y `requestFocus()` tira "FocusRequester is not initialized"; sin
    // reintentar, el foco no aterriza en ningún lado y Android se lo da a lo que se vaya componiendo.
    val menuFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        var landed = false
        repeat(20) {
            if (landed) return@repeat
            landed = runCatching { menuFocus.requestFocus() }.isSuccess
            if (!landed) delay(50)
        }
    }

    // Película: reproduce directo. Serie: abre el detalle, que es donde se elige capítulo.
    // Se navega con la LLAVE DEL GRUPO (`tv:46260`), no con el identifier de la fuente principal:
    // `DetailViewModel.observeGroupMembers` la resuelve a todas las adquisiciones y arma el selector.
    fun abrir(grupo: LibraryGroup) {
        if (grupo.primary.isMovie) {
            scope.launch {
                val ep = graph.repository.firstEpisodeId(grupo.primary.identifier)
                if (ep != null) onPlayEpisode(ep) else onOpenItem(grupo.key)
            }
        } else {
            onOpenItem(grupo.key)
        }
    }

    Row(Modifier.fillMaxSize().background(ArkivBlack)) {
        // --- Menú lateral ---
        Column(
            modifier = Modifier.width(260.dp).fillMaxHeight()
                .background(ArkivSurface)
                .padding(vertical = SAFE_V),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "KINO",
                style = MaterialTheme.typography.titleLarge,
                color = ArkivRed,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(start = SAFE_H, bottom = 28.dp),
            )
            LibrarySection.entries.forEachIndexed { i, s ->
                TvMenuItem(
                    etiqueta = s.label,
                    seleccionada = s == seccion,
                    modifier = if (i == 0) Modifier.focusRequester(menuFocus) else Modifier,
                    // La sección cambia con el FOCO, no con el click: es lo que se espera en un
                    // menú de TV (bajar por el menú va mostrando cada sección), y evita el paso
                    // extra de "enfocar, aceptar, recién ahí ver".
                    onFocus = { seccion = s },
                )
            }
        }

        // --- Contenido ---
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (seccion) {
                LibrarySection.DOWNLOADS -> TvDownloadsSection(onPlayEpisode = onPlayEpisode)
                LibrarySection.WATCHED -> TvPosterGrid(
                    titulo = "Ya visto",
                    conteo = vistos.size,
                    grupos = vistos.map { it.group },
                    subtituloDe = { g ->
                        vistos.firstOrNull { it.group.key == g.key }
                            ?.let { LibraryWatched.watchedLabel(it.episodesWatched) }
                    },
                    vacio = "Todavía no terminaste nada.\nLo que veas hasta el final va a aparecer acá.",
                    onClick = ::abrir,
                    onLongClick = { menuDe = it },
                )
                else -> {
                    // `groups(...)` returns null only for WATCHED/DOWNLOADS, already handled
                    // above: here it's never null.
                    val filtrados = LibraryFilter.groups(seccion, grupos).orEmpty()
                    TvPosterGrid(
                        titulo = seccion.label,
                        conteo = filtrados.size,
                        grupos = filtrados,
                        subtituloDe = { g -> subtituloDeSerie(g) },
                        vacio = "Todavía no guardaste nada acá.\nBuscá algo y dale Guardar.",
                        onClick = ::abrir,
                        onLongClick = { menuDe = it },
                    )
                }
            }
        }
    }

    menuDe?.let { grupo ->
        TvLibraryItemDialog(
            grupo = grupo,
            onOpenDetail = { onOpenItem(grupo.key); menuDe = null },
            onSetCategory = { isMovie -> vm.setCategory(grupo.primary.identifier, isMovie); menuDe = null },
            onQuitar = { vm.quitarGrupo(grupo); menuDe = null },
            onDismiss = { menuDe = null },
        )
    }
}

/** Una entrada del menú lateral. Se pinta como seleccionada cuando su sección es la activa. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvMenuItem(
    etiqueta: String,
    seleccionada: Boolean,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit,
) {
    Surface(
        onClick = onFocus,
        modifier = modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedContainerColor = ArkivRed,
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
    ) {
        Text(
            etiqueta,
            style = MaterialTheme.typography.titleSmall,
            color = if (seleccionada) ArkivTextPrimary else ArkivTextSecondary,
            fontWeight = if (seleccionada) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = SAFE_H, top = 14.dp, end = 20.dp, bottom = 14.dp),
        )
    }
}

/**
 * "24 ep." o "24 ep.  ·  +3 nuevos" si hay capítulos nuevos desde la última vez que se abrió el
 * detalle. Null para películas, que no tienen capítulos.
 *
 * Se reusa el `subtitle` de [TvPosterCard] en lugar de un badge sobre la carátula (como el "+N" de
 * [com.arkiv.player.ui.tv.TvLandscapeCard] en el home) porque esta grilla es la única consumidora
 * de `nuevos` que queda tras borrarse la fila de Series del home (commit f1a9dbbd): agregar un
 * segundo lugar donde pintar un badge —con su propio hueco en la carátula y su franja de color—
 * es más superficie para una sola pantalla, cuando el subtítulo ya existe y tiene lugar de sobra.
 */
private fun subtituloDeSerie(grupo: LibraryGroup): String? {
    if (grupo.primary.isMovie) return null
    val base = "${grupo.episodeCount} ep."
    return if (grupo.nuevos > 0) "$base  ·  +${grupo.nuevos} nuevos" else base
}

/**
 * Grilla de carátulas. `Adaptive` y no un número fijo de columnas: con el menú de 220 dp, en un
 * Fire TV de 1080p entran ~4 columnas de póster, y en una pantalla más ancha entran más solas.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvPosterGrid(
    titulo: String,
    conteo: Int,
    grupos: List<LibraryGroup>,
    subtituloDe: (LibraryGroup) -> String?,
    vacio: String,
    onClick: (LibraryGroup) -> Unit,
    onLongClick: (LibraryGroup) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = SAFE_H, vertical = SAFE_V)) {
        Text(
            if (grupos.isEmpty()) titulo else "$titulo  ·  $conteo",
            style = MaterialTheme.typography.headlineSmall,
            color = ArkivTextPrimary,
            modifier = Modifier.padding(bottom = 20.dp),
        )
        if (grupos.isEmpty()) {
            Text(vacio, style = MaterialTheme.typography.bodyLarge, color = ArkivTextSecondary)
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(grupos, key = { it.key }) { grupo ->
                TvPosterCard(
                    title = grupo.primary.title,
                    posterUrl = grupo.primary.thumbnailUrl,
                    cardHeight = CARD_HEIGHT,
                    subtitle = subtituloDe(grupo),
                    onLongClick = { onLongClick(grupo) },
                    onClick = { onClick(grupo) },
                )
            }
        }
    }
}

/**
 * Menú de mantener-pulsado de una tarjeta. Es el `TvCategoryDialog` que vivía en `TvHomeScreen` más
 * "Quitar de mi biblioteca", que en el TV no existía: hasta ahora un guardado por error solo se
 * podía deshacer desde el teléfono.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvLibraryItemDialog(
    grupo: LibraryGroup,
    onOpenDetail: () -> Unit,
    onSetCategory: (Boolean?) -> Unit,
    onQuitar: () -> Unit,
    onDismiss: () -> Unit,
) {
    val row = grupo.primary
    var confirmarQuitar by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // Mismo patrón de reintento que el menú lateral: un único intento con `runCatching` tragado
    // causó el bug histórico donde, si el diálogo todavía no estaba compuesto, `requestFocus()`
    // tiraba "FocusRequester is not initialized" y el foco quedaba sin dueño.
    LaunchedEffect(confirmarQuitar) {
        var landed = false
        repeat(20) {
            if (landed) return@repeat
            landed = runCatching { focus.requestFocus() }.isSuccess
            if (!landed) delay(50)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ArkivSurface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                row.title,
                style = MaterialTheme.typography.titleMedium,
                color = ArkivTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (confirmarQuitar) {
                Text(
                    "Se quita de tu biblioteca en todos tus aparatos. Si tenías capítulos descargados en este aparato y querés liberar espacio, borralos desde Descargas ANTES de confirmar: una vez que la quitás de acá, esos archivos quedan en el aparato pero ya no vas a poder borrarlos desde la app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ArkivTextSecondary,
                )
                Button(onClick = onQuitar, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth()) {
                    Text("Sí, quitar de mi biblioteca", maxLines = 1)
                }
                // El foco cae acá y NO en el botón de arriba: con el control remoto es normal que
                // un doble OK le llegue a la UI un frame después de lo que el usuario ve, y si el
                // foco arrancara en el botón destructivo ese doble OK lo dispara sin que nadie
                // llegue a leer la advertencia.
                Button(onClick = { confirmarQuitar = false }, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth().focusRequester(focus)) {
                    Text("Cancelar", maxLines = 1)
                }
            } else {
                Text(
                    if (row.isMovie) "Ahora es: Película" else "Ahora es: Serie",
                    style = MaterialTheme.typography.bodySmall,
                    color = ArkivTextSecondary,
                )
                Button(onClick = onOpenDetail, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth().focusRequester(focus)) {
                    Text("Ver detalle / descargar", maxLines = 1)
                }
                if (row.isMovie) {
                    Button(onClick = { onSetCategory(false) }, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth()) {
                        Text("Marcar como serie", maxLines = 1)
                    }
                } else {
                    Button(onClick = { onSetCategory(true) }, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth()) {
                        Text("Marcar como película", maxLines = 1)
                    }
                }
                if (row.categoryOverride != null) {
                    Button(onClick = { onSetCategory(null) }, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth()) {
                        Text("Detección automática", maxLines = 1)
                    }
                }
                Button(onClick = { confirmarQuitar = true }, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth()) {
                    Text("Quitar de mi biblioteca", maxLines = 1)
                }
                Button(onClick = onDismiss, colors = arkivTvButtonColors(), border = arkivTvButtonBorder(), modifier = Modifier.fillMaxWidth()) {
                    Text("Volver", maxLines = 1)
                }
            }
        }
    }
}
