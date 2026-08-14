package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arkiv.player.data.gateway.SeccionDeCatalogo
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay

private val ANCHO_SECCIONES = 300.dp
private val ALTO_SECCION = 52.dp

/**
 * Navegación del catálogo de Magis: secciones a la izquierda, sus contenidos a la derecha.
 *
 * Es la misma forma que [TvCajonDeCanales] —dos columnas, D-pad, foco— pero con pósters en vez de
 * filas de canal. La estructura de datos le calza porque el portal manda los primeros ítems DENTRO
 * de cada sección (`assetList`), así que elegir una sección no cuesta una llamada más.
 *
 * De momento SOLO NAVEGA: elegir un ítem todavía no reproduce. No es una limitación arbitraria —
 * sin reproducción no hay forma de que se escriba historial, así que esta pantalla es segura por
 * construcción. La reproducción entra junto con los guardas de progreso y biblioteca, porque un
 * ítem de una sección de adultos no puede anotarse en ningún lado (el 2026-08-14 una fuga así
 * apareció en la pantalla principal y hubo que limpiarla en el aparato Y en la nube).
 *
 * @param raiz `"series"` o `"adultos"`. La de adultos exige [incluirAdultos]; el gateway responde
 *   409 sin eso, y quien decide es el código por aparato de Ajustes.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSeccionesDeCatalogo(
    raiz: String,
    titulo: String,
    incluirAdultos: Boolean = false,
    onVolver: () -> Unit,
) {
    val graph = rememberGraph()
    var secciones by remember { mutableStateOf<List<SeccionDeCatalogo>>(emptyList()) }
    var elegida by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var cargando by remember { mutableStateOf(true) }

    BackHandler(onBack = onVolver)

    LaunchedEffect(raiz, incluirAdultos) {
        cargando = true
        runCatching { graph.liveApi.arbol(raiz, incluirAdultos) }
            .onSuccess { secciones = it; error = null }
            .onFailure { error = it.message ?: "No se pudo cargar" }
        cargando = false
    }

    val focoSecciones = remember { FocusRequester() }
    LaunchedEffect(secciones.isNotEmpty()) {
        if (secciones.isEmpty()) return@LaunchedEffect
        repeat(20) {
            if (runCatching { focoSecciones.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    Column(Modifier.fillMaxSize().background(ArkivBlack).padding(start = 48.dp, top = 24.dp, end = 24.dp)) {
        Text(titulo, style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Text(
            "Volvé con el botón Atrás del control.",
            style = MaterialTheme.typography.bodySmall,
            color = ArkivTextSecondary,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        when {
            cargando && secciones.isEmpty() -> Mensaje("Cargando…")
            error != null && secciones.isEmpty() -> Mensaje(error!!)
            secciones.isEmpty() -> Mensaje("No hay secciones para mostrar.")
            else -> Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.width(ANCHO_SECCIONES).fillMaxHeight().padding(end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(secciones.size) { i ->
                        FilaDeSeccion(
                            etiqueta = secciones[i].nombre,
                            seleccionada = i == elegida,
                            onClick = { elegida = i },
                            modifier = if (i == 0) Modifier.focusRequester(focoSecciones) else Modifier,
                        )
                    }
                }

                val items = secciones.getOrNull(elegida)?.items.orEmpty()
                if (items.isEmpty()) {
                    Mensaje("Esta sección no trae contenidos.")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(180.dp),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(end = 24.dp, bottom = 24.dp),
                    ) {
                        items(items, key = { it.id }) { item ->
                            Column {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 9f)
                                        .background(ArkivSurface, RoundedCornerShape(8.dp)),
                                ) {
                                    if (item.poster != null) {
                                        AsyncImage(
                                            model = item.poster,
                                            contentDescription = item.titulo,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                                Text(
                                    item.titulo,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FilaDeSeccion(
    etiqueta: String,
    seleccionada: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(ALTO_SECCION),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (seleccionada) ArkivSurface else Color.Transparent,
            focusedContainerColor = ArkivRed,
            contentColor = if (seleccionada) Color.White else ArkivTextSecondary,
            focusedContentColor = Color.White,
        ),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            // Barra roja, igual que en el cajón de canales: el fondo de "seleccionada" solo se
            // distingue del negro por 24 de 255, o sea nada a tres metros de un televisor.
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(if (seleccionada) ArkivRed else Color.Transparent),
            )
            Box(Modifier.fillMaxSize().padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    etiqueta,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (seleccionada) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Mensaje(texto: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(texto, style = MaterialTheme.typography.bodyMedium, color = ArkivTextSecondary)
    }
}
