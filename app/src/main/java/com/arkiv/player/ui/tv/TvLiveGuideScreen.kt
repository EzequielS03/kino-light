package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.ui.live.CATEGORY_FAVORITES
import com.arkiv.player.ui.live.LiveViewModel
import com.arkiv.player.ui.live.LiveZappingSource
import com.arkiv.player.ui.live.filterChannels
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay

/** Alto de cada fila de canal -- grande a propósito, para que se reconozca a tres metros. */
private val ALTO_FILA = 76.dp

/** Ancho fijo del panel de búsqueda (teclado), igual criterio que la columna del teclado en TvSearchScreen. */
private val ANCHO_BUSCADOR = 340.dp

/** Vistas locales que no vienen del gateway -- mismo patrón que `LiveScreen` (mobile). */
private enum class TvVistaLocal { NINGUNA, RECIENTES }

/**
 * Pantalla de "En vivo" del televisor: encontrar un canal y ponerlo. Es una lista de canales
 * (chips de categoría + buscador + filas), no una guía de programación.
 *
 * CAUSA RAÍZ del rediseño (verificada contra el portal real y contra el APK decompilado de Magis,
 * incluida la petición equivalente a la de la app oficial): `programList` viene SIEMPRE vacío --
 * la plataforma no sirve programación, solo agenda deportiva (que es otra cosa). La versión
 * anterior de esta pantalla era una guía canal×hora con el foco y el click puestos en los bloques
 * de programa (`TvBloquePrograma`); sin programación esos bloques nunca existían, así que no había
 * nada que enfocar ni nada que reproducir con OK, y las filas se veían todas iguales porque el
 * foco vivía en un elemento que nunca se pintaba. El dueño lo pidió explícito: "si no hay
 * programación no nos desgastemos en eso ni en el tv ni el celular". Acá se sacó timeline, cabecera
 * de horas, línea de "ahora" y el pedido de EPG por fila -- todo era, en la práctica, código muerto
 * (nunca tenía datos que mostrar). El celular NO se toca: su guía (`LiveGuideList.kt`) ya maneja
 * "sin programación" con un texto y sigue andando por su propio botón "Ver ahora", así que no
 * comparte el problema de foco/click de esta pantalla y no había nada que arreglarle ahí.
 * `currentProgram`/`progressOf` (`LiveGuideList.kt`) siguen usándose ahí sin cambios; `anchoDp`/`ventanaDe`
 * (los helpers del timeline, solo usados por ESTA pantalla) se borraron junto con sus tests.
 *
 * Foco con el mando -- las CUATRO direcciones quedan cubiertas por la navegación estándar de
 * Compose entre elementos `focusable`/`Surface`, sin escapes a mano (no hace falta: ya no hay
 * timelines horizontales de las que "escapar"):
 * - Arriba/Abajo: dentro del teclado (grilla), dentro de los chips (una sola fila, no se mueve) y
 *   entre chips ↔ primera fila de canal ↔ resto de filas del `LazyColumn` -- Compose busca el
 *   foco más cercano en esa dirección, y como todo está apilado verticalmente en una columna
 *   angosta el resultado es predecible.
 * - Izquierda/Derecha: entre el panel del teclado (columna fija a la izquierda) y el panel de
 *   chips+filas (a la derecha) -- mismo mecanismo de búsqueda 2D que ya usa `TvSearchScreen` para
 *   moverse entre su teclado y su grilla de resultados, sin código extra.
 * El foco inicial es el primer chip ("Favoritos"): entrar a la pantalla debe mostrar algo para
 * navegar de una, no arrancar parado en el teclado.
 *
 * Reproducir: la FILA es el elemento enfocable y clicable (no un sub-bloque adentro). Pulsar OK
 * sobre una fila llama a `onVerCanal` directo -- funciona haya o no programación, porque ya no
 * depende de que exista programación.
 *
 * Buscador: reusa `TvKeyboard` (mismo patrón visual y de foco que `TvSearchScreen`) y `filterChannels`
 * (`LiveViewModel.kt`, ya filtra por nombre sin tildes/mayúsculas y por número exacto -- la misma
 * función que usa la guía del celular). A diferencia de `TvSearchScreen` (que busca en TMDB por
 * red y por eso espera al botón "Buscar"), acá el filtro es sobre la lista de canales YA cargada
 * en memoria -- filtra en cada tecla, sin ida y vuelta de red que justifique un botón.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvLiveGuideScreen(onVerCanal: (LiveChannel) -> Unit, onVolver: () -> Unit) {
    val graph = rememberGraph()
    val vm: LiveViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                LiveViewModel(
                    graph.catalogoDeVivo, graph.database.liveFavoriteDao(),
                    graph.database.liveChannelCacheDao(),
                    // Se lee en CADA carga, no una vez: destrabar 18+ desde Ajustes tiene
                    // que verse al volver a entrar, sin reiniciar la app.
                    adultsUnlocked = { graph.settings.adultosDesbloqueado.value },
                )
            }
        },
    )
    val estado by vm.state.collectAsStateWithLifecycle()

    var vista by remember { mutableStateOf(TvVistaLocal.NINGUNA) }

    BackHandler { onVolver() }

    // Recientes: igual que LiveScreen (mobile) -- se lee directo de Room, sin numero/logo propios,
    // enriquecido con lo que ya esté cargado en estado.channels si el canal aparece ahí.
    val recentDao = remember { graph.database.liveRecentDao() }
    val recientesCrudo by recentDao.flowRecent().collectAsStateWithLifecycle(initialValue = emptyList())
    val recientes = remember(recientesCrudo, estado.channels) {
        recientesCrudo.map { r ->
            estado.channels.find { it.code == r.code }?.copy(nombre = r.nombre)
                ?: LiveChannel(r.code, r.nombre, 0, null)
        }
    }

    val canalesBase = if (vista == TvVistaLocal.RECIENTES) recientes else estado.channels

    // El buscador filtra por encima de la categoría/vista activa, igual que LiveScreen (mobile):
    // buscar no reemplaza la categoría elegida, la acota.
    var busqueda by remember { mutableStateOf("") }
    val canales = remember(canalesBase, busqueda) { filterChannels(canalesBase, busqueda) }

    // Ver el canal ahora: fija en LiveZappingSource la lista FILTRADA (con la que el usuario está
    // mirando ahora mismo) ANTES de delegar a `onVerCanal` -- es la que el zapping del reproductor
    // recorre. Mismo criterio que `LiveScreen.open` (mobile): si hay una búsqueda activa, el
    // zapping recorre los resultados de la búsqueda, no la categoría entera.
    fun verCanal(canal: LiveChannel) {
        LiveZappingSource.list = canales
        onVerCanal(canal)
    }

    // Foco inicial de la pantalla: el primer chip ("Favoritos"), para que entrar a la pantalla
    // muestre algo navegable de una en vez de arrancar parado en el teclado.
    val chipsFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        var landed = false
        repeat(20) {
            if (landed) return@repeat
            landed = runCatching { chipsFocus.requestFocus() }.isSuccess
            if (!landed) delay(50)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(ArkivBlack)
            .padding(start = 48.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
    ) {
        Text(
            "En vivo",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        Row(Modifier.fillMaxSize()) {
            // --- Panel izquierdo: buscador (mismo patrón visual que TvSearchScreen). ---
            Column(modifier = Modifier.fillMaxHeight().width(ANCHO_BUSCADOR).padding(end = 24.dp)) {
                Text(
                    busqueda.ifBlank { "Buscar canal por nombre o número…" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (busqueda.isBlank()) ArkivTextSecondary else Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                TvKeyboard(text = busqueda, onTextChange = { busqueda = it })
                if (busqueda.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        onClick = { busqueda = "" },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                        colors = arkivTvSurfaceColors(),
                        border = arkivTvSurfaceBorder(),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Borrar búsqueda", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // --- Panel derecho: chips de categoría + lista de canales. ---
            Column(Modifier.weight(1f).fillMaxHeight()) {
                LazyRow(
                    contentPadding = PaddingValues(end = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        TvCategoriaChip(
                            label = "Favoritos",
                            icon = Icons.Default.Star,
                            selected = vista == TvVistaLocal.NINGUNA && estado.activeCategory == CATEGORY_FAVORITES,
                            onClick = { vista = TvVistaLocal.NINGUNA; vm.chooseCategory(CATEGORY_FAVORITES) },
                            modifier = Modifier.focusRequester(chipsFocus),
                        )
                    }
                    item {
                        TvCategoriaChip(
                            label = "Recientes",
                            icon = Icons.Default.History,
                            selected = vista == TvVistaLocal.RECIENTES,
                            onClick = { vista = TvVistaLocal.RECIENTES },
                        )
                    }
                    items(estado.categories, key = { it.id }) { cat ->
                        TvCategoriaChip(
                            label = cat.nombre,
                            icon = null,
                            selected = vista == TvVistaLocal.NINGUNA && estado.activeCategory == cat.id,
                            onClick = { vista = TvVistaLocal.NINGUNA; vm.chooseCategory(cat.id) },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                when {
                    vista == TvVistaLocal.RECIENTES && canalesBase.isEmpty() ->
                        TvGuiaMensaje("Sin canales recientes", "Los canales que abras van a aparecer acá.")
                    estado.error != null && estado.channels.isEmpty() ->
                        TvGuiaMensaje(estado.error!!, "Presioná OK para reintentar.") { vm.chooseCategory(estado.activeCategory) }
                    estado.loading && estado.channels.isEmpty() ->
                        TvGuiaMensaje("Cargando canales…", null)
                    busqueda.isNotBlank() && canales.isEmpty() ->
                        TvGuiaMensaje("Sin resultados", "Probá con otro nombre o número de canal.")
                    canalesBase.isEmpty() ->
                        TvGuiaMensaje("Sin canales", "No encontramos canales en esta categoría.")
                    else -> LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(end = 24.dp, bottom = 16.dp),
                    ) {
                        items(canales, key = { it.code }) { canal ->
                            TvCanalRow(
                                canal = canal,
                                onClick = { verCanal(canal) },
                                modifier = Modifier.height(ALTO_FILA),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Una fila de canal: el elemento enfocable y clicable de la pantalla (ya no un sub-bloque de
 * programa adentro de ella). Pulsar OK reproduce el canal directo, haya o no programación.
 *
 * `Surface` (tv-material3), no `Box` + `clickable` + `focusable` a mano como tenía el bloque de
 * programa anterior: es el mismo componente que ya usan `TvRefineRow`/`TvSeasonChip` para filas
 * navegables, y el foco (fondo rojo + borde blanco de 3dp) se ve con claridad a tres metros.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvCanalRow(canal: LiveChannel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ArkivSurface,
            focusedContainerColor = ArkivRed,
            pressedContainerColor = ArkivRed,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White)),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Logo -- ya funciona (posterList[].fileUrl): es la forma más rápida de reconocer un
            // canal de un vistazo. Si no hay logo, el número hace de reemplazo (mismo criterio que
            // ChannelCard/GuideChannelRow, que ya resuelven este fallback).
            Box(
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(ArkivSurfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (canal.logo != null) {
                    AsyncImage(
                        model = canal.logo,
                        contentDescription = canal.nombre,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                    )
                } else {
                    Text(
                        canal.numero.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }
            Column {
                Text(
                    canal.nombre,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Número siempre visible, no solo como fallback del logo: es lo que el buscador
                // matchea por número exacto, así que conviene verlo también cuando el logo sí está.
                Text(
                    "Canal ${canal.numero}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ArkivTextSecondary,
                )
            }
        }
    }
}

/** Chip de categoría con el mismo tratamiento visual manual que `TvSourceChip` (TvEpisodeChip.kt). */
@Composable
private fun TvCategoriaChip(
    label: String,
    icon: ImageVector?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var enfocado by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .onFocusChanged { enfocado = it.isFocused }
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) ArkivRed else if (enfocado) ArkivSurfaceHigh else ArkivSurface)
            .border(
                width = if (enfocado) 2.dp else 0.dp,
                color = if (enfocado) Color.White else Color.Transparent,
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
    }
}

/** Mensaje centrado simple, con reintentar opcional -- para "sin canales"/"cargando"/error/búsqueda vacía. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvGuiaMensaje(titulo: String, subtitulo: String?, onReintentar: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(titulo, style = MaterialTheme.typography.titleMedium, color = Color.White)
        if (subtitulo != null) {
            Text(
                subtitulo,
                style = MaterialTheme.typography.bodyMedium,
                color = ArkivTextSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (onReintentar != null) {
            Button(
                onClick = onReintentar,
                colors = arkivTvButtonColors(),
                border = arkivTvButtonBorder(),
                modifier = Modifier.padding(top = 16.dp),
            ) { Text("Reintentar") }
        }
    }
}
