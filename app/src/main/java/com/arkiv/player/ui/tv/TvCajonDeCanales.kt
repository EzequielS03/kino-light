package com.arkiv.player.ui.tv

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.ui.live.CATEGORY_FAVORITES
import com.arkiv.player.ui.live.DrawerFocus
import com.arkiv.player.ui.live.DrawerIndex
import com.arkiv.player.ui.live.LiveViewModel
import com.arkiv.player.ui.live.filterChannels
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay

/** Ancho del cajón. Deja ver el video a la derecha: es un cajón, no otra pantalla. */
private val ANCHO_CAJON = 560.dp
private val ANCHO_CATEGORIAS = 200.dp
private val ALTO_ITEM = 52.dp

/**
 * El cajón de canales del vivo: se abre con la flecha izquierda sobre el video que se está
 * viendo, lista TODO el catálogo por categorías, tiene buscador, y se cierra con la derecha.
 *
 * Es un cajón y no la guía completa ([TvLiveGuideScreen]) a propósito: la guía es una pantalla
 * a la que se va, y el punto de esto es cambiar de canal SIN dejar de ver lo que estás viendo.
 * Por eso ocupa una franja y el video sigue corriendo al lado.
 *
 * Las flechas las decide [com.arkiv.player.ui.live.DrawerDpad], que es puro y está cubierto
 * por tests: acá solo se pinta y se mueve el foco. Esa separación no es ceremonia — el proyecto
 * no tiene tests de interfaz, así que una regla de navegación escrita adentro de un composable
 * no se puede probar de ninguna forma.
 *
 * @param foco en qué columna está el foco; lo gobierna quien llama (el reproductor), porque es
 *   quien recibe las teclas del control mientras el video tiene el foco de Android.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvCajonDeCanales(
    foco: DrawerFocus,
    onFoco: (DrawerFocus) -> Unit,
    onElegirCanal: (List<LiveChannel>, LiveChannel) -> Unit,
    canalActual: String?,
) {
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
    var busqueda by remember { mutableStateOf("") }
    val canales = remember(estado.channels, busqueda) { filterChannels(estado.channels, busqueda) }

    val focoCategorias = remember { FocusRequester() }
    val focoCanales = remember { FocusRequester() }
    val focoTeclado = remember { FocusRequester() }

    // UN solo índice para poner la fila a la vista Y para decidir cuál lleva el FocusRequester.
    // Antes eran dos efectos distintos —uno hacía scroll al canal en vivo, el otro pedía foco
    // sobre el ítem 0— y peleaban: pedirle foco a la primera fila arrastra la lista entera de
    // vuelta al principio. Con 1040 canales eso se veía como un scroll interminable hacia arriba
    // que terminaba lejos del canal que se estaba mirando. Ver [DrawerIndex].
    val listaCanales = rememberLazyListState()
    val indiceActual = remember(canales, canalActual) { DrawerIndex.indexFor(canales, canalActual) }

    // El foco de Android tarda en existir: la fila a la que hay que ir puede no estar compuesta
    // todavía cuando cambia `foco`. Se reintenta un rato corto en vez de pedirlo una sola vez --
    // mismo patrón que ya usa TvLiveGuideScreen para su chip inicial.
    LaunchedEffect(foco, indiceActual, canales.isEmpty()) {
        val destino = when (foco) {
            DrawerFocus.CATEGORIES -> focoCategorias
            DrawerFocus.CHANNELS -> if (canales.isEmpty()) focoCategorias else focoCanales
            DrawerFocus.KEYBOARD -> focoTeclado
        }
        // Posicionar ANTES de pedir el foco, y sin animar: una fila que no está compuesta no
        // puede recibirlo, y el intento hace saltar la lista a la que sí lo está.
        if (destino === focoCanales) listaCanales.scrollToItem(indiceActual)
        repeat(20) {
            if (runCatching { destino.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    Row(
        Modifier
            .fillMaxHeight()
            .width(ANCHO_CAJON)
            // Negro casi opaco, no del todo: deja intuir el video detrás y recuerda que no te
            // fuiste a otra pantalla.
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(start = 32.dp, end = 16.dp, top = 24.dp, bottom = 16.dp),
    ) {
        Column(Modifier.width(ANCHO_CATEGORIAS).fillMaxHeight().padding(end = 12.dp)) {
            if (foco == DrawerFocus.KEYBOARD) {
                Text(
                    busqueda.ifBlank { "Escribí para buscar…" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (busqueda.isBlank()) ArkivTextSecondary else Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                TvKeyboard(
                    text = busqueda,
                    onTextChange = { busqueda = it },
                    firstKeyFocus = focoTeclado,
                )
            } else {
                CajonItem(
                    etiqueta = if (busqueda.isBlank()) "Buscar…" else "“$busqueda”",
                    seleccionado = busqueda.isNotBlank(),
                    onClick = { onFoco(DrawerFocus.KEYBOARD) },
                    modifier = Modifier.focusRequester(focoCategorias),
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    item {
                        CajonItem(
                            etiqueta = "Favoritos",
                            seleccionado = estado.activeCategory == CATEGORY_FAVORITES,
                            onClick = { vm.chooseCategory(CATEGORY_FAVORITES) },
                        )
                    }
                    items(estado.categories, key = { it.id }) { cat ->
                        CajonItem(
                            etiqueta = cat.nombre,
                            seleccionado = estado.activeCategory == cat.id,
                            onClick = { vm.chooseCategory(cat.id) },
                        )
                    }
                }
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight()) {
            when {
                estado.error != null && estado.channels.isEmpty() ->
                    CajonMensaje(estado.error!!)
                estado.loading && estado.channels.isEmpty() ->
                    CajonMensaje("Cargando canales…")
                canales.isEmpty() && busqueda.isNotBlank() ->
                    CajonMensaje("Sin resultados para “$busqueda”")
                canales.isEmpty() ->
                    CajonMensaje("Sin canales en esta categoría")
                else -> LazyColumn(
                    state = listaCanales,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    itemsIndexed(canales) { i, canal ->
                        CajonFilaDeCanal(
                            canal = canal,
                            enPantalla = canal.code == canalActual,
                            // Elegir cambia el canal y cierra: quien llama decide las dos cosas.
                            // Se le pasa la lista FILTRADA porque es la que el zapping de
                            // arriba/abajo tiene que recorrer después -- si buscaste "deportes",
                            // zapear debería moverse entre esos, no entre el catálogo entero.
                            onClick = { onElegirCanal(canales, canal) },
                            modifier = if (i == indiceActual) Modifier.focusRequester(focoCanales) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

/** `itemsIndexed` de la lista perezosa, con la key estable del canal. */
private inline fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    canales: List<LiveChannel>,
    crossinline fila: @Composable (Int, LiveChannel) -> Unit,
) = items(count = canales.size, key = { canales[it].code }) { i -> fila(i, canales[i]) }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CajonItem(
    etiqueta: String,
    seleccionado: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(ALTO_ITEM),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (seleccionado) ArkivSurface else Color.Transparent,
            focusedContainerColor = ArkivRed,
            contentColor = if (seleccionado) Color.White else ArkivTextSecondary,
            focusedContentColor = Color.White,
        ),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            // Una barra ROJA a la izquierda, no solo el fondo. El fondo de "seleccionado" es
            // ArkivSurface (#181818) sobre un cajón casi negro: 24 de 255 de diferencia, o sea
            // invisible en un televisor a tres metros. Se notó al usarlo -- "no me queda
            // seleccionada la categoría" -- y la comparación fue exacta: el canal en pantalla SÍ
            // se distingue, porque tiene un "● EN VIVO" rojo. Esto le da a la categoría la misma
            // señal, en el mismo idioma visual.
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(if (seleccionado) ArkivRed else Color.Transparent),
            )
            Box(
                Modifier.fillMaxSize().padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    etiqueta,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (seleccionado) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CajonFilaDeCanal(
    canal: LiveChannel,
    enPantalla: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(ALTO_ITEM),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (enPantalla) ArkivSurface else Color.Transparent,
            focusedContainerColor = ArkivRed,
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canal.numero > 0) {
                Text(
                    canal.numero.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = ArkivTextSecondary,
                    modifier = Modifier.width(44.dp),
                )
            }
            Text(
                canal.nombre,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (enPantalla) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Marca cuál se está viendo: sin esto, con el cajón tapando media pantalla se pierde
            // la referencia de dónde estás parado.
            if (enPantalla) {
                Text("● EN VIVO", style = MaterialTheme.typography.labelSmall, color = ArkivRed)
            }
        }
    }
}

@Composable
private fun CajonMensaje(texto: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            texto,
            style = MaterialTheme.typography.bodyMedium,
            color = ArkivTextSecondary,
            modifier = Modifier.padding(16.dp),
        )
    }
}
