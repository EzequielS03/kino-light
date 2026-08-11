package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveProgram
import com.arkiv.player.ui.live.CATEGORIA_FAVORITOS
import com.arkiv.player.ui.live.LiveViewModel
import com.arkiv.player.ui.live.avance
import com.arkiv.player.ui.live.enCurso
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Dp por hora del timeline. 300 dp/h deja ver ~4 h en una pantalla de TV de 1280 dp. */
const val DP_POR_HORA = 300f
private const val ANCHO_MINIMO_DP = 40f

/** Ancho del bloque de un programa, con un mínimo para que siempre se pueda enfocar. */
fun anchoDp(p: LiveProgram): Float =
    (((p.fin - p.inicio) / 3600f) * DP_POR_HORA).coerceAtLeast(ANCHO_MINIMO_DP)

/** La ventana visible: desde la hora en punto anterior a [instante], por 6 horas. */
fun ventanaDe(instante: Long): Pair<Long, Long> {
    val inicio = instante - (instante % 3600)
    return inicio to (inicio + 6 * 3600)
}

/** Ancho fijo de la columna de identidad del canal, a la izquierda de cada fila. */
private val ANCHO_CANAL_FIJO = 280.dp

/** Alto de cada fila de canal. */
private val ALTO_FILA = 64.dp

/** Dominio total del timeline: un día completo (00:00-24:00), fijo -- ver KDoc de [dpDeEpoch]. */
private const val HORAS_DEL_DOMINIO = 24

private val horaFormatter = DateTimeFormatter.ofPattern("HH:mm")
private fun horaDe(epochSegundos: Long): String =
    Instant.ofEpochSecond(epochSegundos).atZone(ZoneId.systemDefault()).format(horaFormatter)

/** Posición en dp de [epoch] dentro del timeline, relativa al origen (medianoche local) [origen]. */
private fun dpDeEpoch(epoch: Long, origen: Long): Float = (epoch - origen) / 3600f * DP_POR_HORA

/** Vistas locales que no vienen del gateway -- mismo patrón que `LiveScreen` (mobile). */
private enum class TvVistaLocal { NINGUNA, RECIENTES }

/**
 * Guía de programación del TV: canal × hora, recorrida con el mando como un decodificador de
 * cable. Es la pantalla principal de "En vivo" en el televisor (Tarea 13).
 *
 * No exige cuenta de Magis vinculada: el catálogo de canales usa la sesión anónima del gateway
 * (por número de serie del dispositivo, igual que el CLI de magia) cuando no hay cuenta
 * vinculada -- ver `MagisSession` en el gateway. Si el usuario SÍ tiene cuenta vinculada,
 * `LiveApi` ya manda su `X-Arkiv-Account` de todos modos, sin que esta pantalla tenga que saber
 * nada al respecto.
 *
 * Arma chips de categoría (Favoritos/Recientes primero), la cabecera de horas y un `LazyColumn`
 * de `TvGuiaFilaCanal` (un `Row` con la identidad fija del canal + un timeline scrolleable de
 * `TvBloquePrograma`, o un bloque estático fijo -- `TvBloqueCargando`/`TvBloqueVacio` -- mientras
 * no hay programas que recorrer).
 *
 * Foco con el mando:
 * - Arriba/abajo: navegación estándar de Compose entre los bloques de la fila anterior/siguiente
 *   dentro del `LazyColumn` (no hace falta código extra: cada bloque es un foco más y Compose
 *   busca el más cercano en esa dirección).
 * - Izquierda/derecha: se mueve el foco entre los bloques de programa de la fila. Como TODAS las
 *   filas comparten el mismo [ScrollState] (ver [scrollCompartido] en [TvLiveGuideScreen]),
 *   mover el scroll de una fila mueve el de todas -- así las horas quedan alineadas entre
 *   canales. Se comparte un `ScrollState` (offset en PÍXELES), no un `LazyListState` por índice:
 *   cada canal tiene un número distinto de programas con anchos distintos, así que el índice N de
 *   un canal no representa el mismo instante que el índice N de otro -- solo un offset en píxeles
 *   (equivalente a un instante absoluto, ver [dpDeEpoch]) es una referencia común válida.
 * - En el primer bloque de cada fila, Izquierda devuelve el foco al primer chip de categoría
 *   ("Favoritos") en vez de perderse: mismo PATRÓN que `dpadFocusEscape` (`TvComponents.kt:55`,
 *   que resuelve el mismo problema para Arriba/Abajo saliendo de un `TextField`), pero para
 *   Izquierda -- esa función no sirve tal cual acá porque solo mira esas dos teclas.
 *
 * Estados: esqueleto gris "Cargando programación…" por fila mientras no llega su EPG (nunca
 * bloquea: se pide con [LiveViewModel.pedirEpgDe] solo para las filas visibles + margen, ver el
 * `LaunchedEffect` de `listState` más abajo); mensaje simple + reintentar si la carga de canales
 * falla del todo.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvLiveGuideScreen(onVerCanal: (LiveChannel) -> Unit, onVolver: () -> Unit) {
    val graph = rememberGraph()
    val vm: LiveViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                LiveViewModel(graph.liveApi, graph.database.liveFavoriteDao(), graph.database.liveChannelCacheDao())
            }
        },
    )
    val estado by vm.estado.collectAsStateWithLifecycle()

    var vista by remember { mutableStateOf(TvVistaLocal.NINGUNA) }
    // Ficha del programa tocado que no está en curso (canal + programa, futuro o ya terminado);
    // null = sin diálogo abierto.
    var dialogo by remember { mutableStateOf<Pair<LiveChannel, LiveProgram>?>(null) }

    // Atrás cierra primero el diálogo si está abierto, no la pantalla entera -- mismo patrón que
    // TvLibraryScreen (BackHandler(enabled = menuDe == null)).
    BackHandler(enabled = dialogo == null) { onVolver() }

    // Recientes: igual que LiveScreen (mobile) -- se lee directo de Room, sin numero/logo propios,
    // enriquecido con lo que ya esté cargado en estado.canales si el canal aparece ahí.
    val recentDao = remember { graph.database.liveRecentDao() }
    val recientesCrudo by recentDao.flowUltimos().collectAsStateWithLifecycle(initialValue = emptyList())
    val recientes = remember(recientesCrudo, estado.canales) {
        recientesCrudo.map { r ->
            estado.canales.find { it.code == r.code }?.copy(nombre = r.nombre)
                ?: LiveChannel(r.code, r.nombre, 0, null)
        }
    }
    LaunchedEffect(recientes) {
        if (recientes.isNotEmpty()) vm.pedirEpgDe(recientes.take(40).map { it.code })
    }

    val canales = if (vista == TvVistaLocal.RECIENTES) recientes else estado.canales

    // Foco inicial de la pantalla: el primer chip ("Favoritos"). También es el destino de la
    // "fuga" desde el borde izquierdo del timeline (ver KDoc de TvLiveGuideScreen).
    val chipsFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        var landed = false
        repeat(20) {
            if (landed) return@repeat
            landed = runCatching { chipsFocus.requestFocus() }.isSuccess
            if (!landed) delay(50)
        }
    }

    // Reloj del timeline: se recalcula en cada recomposición en vez de con un ticker propio --
    // igual decisión que GuiaCanalRow (LiveGuideList.kt), que ya ocurren seguido acá (foco
    // moviéndose, EPG llegando).
    val ahoraSegundos = System.currentTimeMillis() / 1000
    val origenDelDia = Instant.ofEpochSecond(ahoraSegundos).atZone(ZoneId.systemDefault())
        .toLocalDate().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()

    // Dominio fijo de 24 h (medianoche a medianoche local): todas las filas, la cabecera de horas
    // y la línea de "ahora" comparten este mismo origen, así que quedan alineadas entre sí aunque
    // la EPG de un canal no arranque exactamente a las 00:00 -- cada programa se posiciona por su
    // epoch ABSOLUTO (dpDeEpoch), no acumulado programa a programa dentro de la fila.
    val dominioTotalDp = HORAS_DEL_DOMINIO * DP_POR_HORA

    val scrollCompartido = rememberScrollState()
    val density = LocalDensity.current
    // Arranca con la ventana visible en la hora en punto actual (ventanaDe), no en las 00:00.
    LaunchedEffect(Unit) {
        val ventana = ventanaDe(ahoraSegundos)
        val destinoPx = with(density) { dpDeEpoch(ventana.first, origenDelDia).dp.toPx() }
            .toInt().coerceAtLeast(0)
        scrollCompartido.scrollTo(destinoPx)
    }

    val listState = rememberLazyListState()
    // Pide la EPG de las filas visibles + margen -- nunca de todo el catálogo (ver brief:
    // "Solo se pide la programación de las filas visibles más un margen"). Mismo mecanismo que
    // LiveGuideList (snapshotFlow sobre los índices visibles), con margen porque acá el usuario
    // navega con el mando (más lento de "asomar" una fila nueva que un scroll táctil).
    LaunchedEffect(listState, canales) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collect { indices ->
                if (indices.isEmpty()) return@collect
                val margen = 3
                val desde = (indices.min() - margen).coerceAtLeast(0)
                val hasta = (indices.max() + margen).coerceAtMost(canales.lastIndex)
                if (desde > hasta) return@collect
                val faltantes = (desde..hasta).mapNotNull { canales.getOrNull(it)?.code }
                    .filter { it !in estado.programacion }
                if (faltantes.isNotEmpty()) vm.pedirEpgDe(faltantes)
            }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(ArkivBlack)
            .padding(start = 48.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
    ) {
        Text(
            "Guía de programación",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        LazyRow(
            contentPadding = PaddingValues(end = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                TvCategoriaChip(
                    label = "Favoritos",
                    icon = Icons.Default.Star,
                    selected = vista == TvVistaLocal.NINGUNA && estado.categoriaActiva == CATEGORIA_FAVORITOS,
                    onClick = { vista = TvVistaLocal.NINGUNA; vm.elegirCategoria(CATEGORIA_FAVORITOS) },
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
            items(estado.categorias, key = { it.id }) { cat ->
                TvCategoriaChip(
                    label = cat.nombre,
                    icon = null,
                    selected = vista == TvVistaLocal.NINGUNA && estado.categoriaActiva == cat.id,
                    onClick = { vista = TvVistaLocal.NINGUNA; vm.elegirCategoria(cat.id) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        when {
            vista == TvVistaLocal.RECIENTES && canales.isEmpty() ->
                TvGuiaMensaje("Sin canales recientes", "Los canales que abras van a aparecer acá.")
            estado.error != null && estado.canales.isEmpty() ->
                TvGuiaMensaje(estado.error!!, "Presioná OK para reintentar.") { vm.elegirCategoria(estado.categoriaActiva) }
            estado.cargando && canales.isEmpty() ->
                TvGuiaMensaje("Cargando canales…", null)
            canales.isEmpty() ->
                TvGuiaMensaje("Sin canales", "No encontramos canales en esta categoría.")
            else -> Box(Modifier.weight(1f)) {
                Column(Modifier.fillMaxSize()) {
                    // --- Cabecera de horas: mismo scroll compartido que las filas de abajo. ---
                    Row(Modifier.fillMaxWidth().height(28.dp)) {
                        Spacer(Modifier.width(ANCHO_CANAL_FIJO))
                        // OJO acá (hallazgo de revisión, verificado antes de aplicar el fix): un
                        // solo Box con `.width(dominioTotalDp.dp).horizontalScroll(...)` NO alcanza.
                        // `.width()` usa `Constraints.constrain()`, que RECORTA el ancho pedido al
                        // máximo que el padre (este `weight(1f)`, acotado a la pantalla real) ya
                        // le ofrece -- así que el Box terminaba con el mismo ancho que su propio
                        // viewport y `ScrollState.maxValue` quedaba en 0 (nada para desplazar).
                        // La solución NO es solo cambiar `width` por `requiredWidth` en el mismo
                        // lugar (lo probé: `requiredWidth` ahí fuerza el tamaño de TODO el nodo,
                        // incluido el propio `horizontalScroll`, así que el "viewport" también
                        // pasaría a medir 7200dp y `maxValue` seguiría en 0, solo que por la razón
                        // opuesta). Hacen falta DOS Box distintos: el de afuera (`weight` +
                        // `horizontalScroll`) recibe el ancho REAL de pantalla y es el viewport; el
                        // de adentro (`requiredWidth`) es el que la búsqueda de foco 2D encuentra
                        // como contenido -- `horizontalScroll` mide a SU hijo con ancho infinito
                        // (así es como sabe cuánto hay para desplazar), así que ahí adentro
                        // `requiredWidth` sí consigue los 7200dp completos.
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight()
                                .horizontalScroll(scrollCompartido),
                        ) {
                            Box(modifier = Modifier.requiredWidth(dominioTotalDp.dp).fillMaxHeight()) {
                                repeat(HORAS_DEL_DOMINIO) { h ->
                                    Box(
                                        modifier = Modifier.offset(x = (h * DP_POR_HORA).dp)
                                            .width(DP_POR_HORA.dp).fillMaxHeight(),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        Text(
                                            "%02d:00".format(h),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = ArkivTextSecondary,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(canales, key = { it.code }) { canal ->
                            TvGuiaFilaCanal(
                                canal = canal,
                                programas = estado.programacion[canal.code],
                                ahoraSegundos = ahoraSegundos,
                                origenDelDia = origenDelDia,
                                scrollCompartido = scrollCompartido,
                                dominioTotalDp = dominioTotalDp,
                                chipsFocus = chipsFocus,
                                onVerCanal = onVerCanal,
                                onDetalle = { p -> dialogo = canal to p },
                                modifier = Modifier.height(ALTO_FILA),
                            )
                        }
                    }
                }

                // Línea de "ahora": se ve moverse a la izquierda a medida que el scroll
                // compartido avanza en el tiempo, igual que en un decodificador de cable real.
                val scrollPx = scrollCompartido.value
                val nowXPx = with(density) { dpDeEpoch(ahoraSegundos, origenDelDia).dp.toPx() }.toInt() - scrollPx
                Box(
                    modifier = Modifier.padding(start = ANCHO_CANAL_FIJO)
                        .offset { IntOffset(nowXPx, 0) }
                        .fillMaxHeight()
                        .width(2.dp)
                        .background(ArkivRed),
                )
            }
        }
    }

    dialogo?.let { (canal, programa) ->
        TvProgramaDialog(
            canal = canal,
            programa = programa,
            ahoraSegundos = ahoraSegundos,
            onVerAhora = { onVerCanal(canal); dialogo = null },
            onDismiss = { dialogo = null },
        )
    }
}

/** Una fila: identidad del canal (fija) + su timeline de programas (scroll compartido). */
@Composable
private fun TvGuiaFilaCanal(
    canal: LiveChannel,
    programas: List<LiveProgram>?,
    ahoraSegundos: Long,
    origenDelDia: Long,
    scrollCompartido: ScrollState,
    dominioTotalDp: Float,
    chipsFocus: FocusRequester,
    onVerCanal: (LiveChannel) -> Unit,
    /** Programa que NO está en curso -- puede ser futuro o ya terminado, ver [TvProgramaDialog]. */
    onDetalle: (LiveProgram) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // --- Identidad del canal: fija, no scrollea. ---
        Row(
            modifier = Modifier.width(ANCHO_CANAL_FIJO).fillMaxHeight().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(ArkivSurfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (canal.logo != null) {
                    AsyncImage(
                        model = canal.logo,
                        contentDescription = canal.nombre,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                    )
                } else {
                    Text(
                        canal.numero.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }
            Text(
                canal.nombre,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // --- Timeline: comparte el scroll con el resto de las filas y la cabecera. ---
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (programas == null) {
                // Esqueleto -- nunca un spinner que bloquee (ver brief). Sigue enfocable para que
                // Arriba/Abajo entre canales no se rompa mientras la EPG todavía no llegó. Bloque
                // chico y ESTÁTICO (no adentro de horizontalScroll): así el aviso de "cargando"
                // no depende de en qué hora está parado el scroll compartido -- siempre visible.
                TvBloqueCargando(chipsFocus)
            } else if (programas.isEmpty()) {
                // Mismo criterio que el esqueleto: sin programas no hay nada que recorrer, así que
                // el aviso queda fijo junto al nombre del canal en vez de colgar de una posición
                // horaria que podría scrollearse fuera de vista.
                TvBloqueVacio(onClick = { onVerCanal(canal) }, chipsFocus = chipsFocus)
            } else {
                val actual = enCurso(programas, ahoraSegundos)
                // Dos Box, no uno: `horizontalScroll` (afuera, recibe el ancho REAL del viewport
                // vía el `weight(1f)` de arriba) + `requiredWidth` (adentro, fuerza el ancho
                // completo del dominio de 24h contra el ancho infinito que `horizontalScroll` le
                // da a SU hijo) -- ver el comentario largo en la cabecera de horas, mismo problema
                // y misma solución acá.
                Box(modifier = Modifier.fillMaxHeight().horizontalScroll(scrollCompartido)) {
                    Box(modifier = Modifier.requiredWidth(dominioTotalDp.dp).fillMaxHeight()) {
                        programas.forEachIndexed { i, p ->
                            val esActual = p == actual
                            TvBloquePrograma(
                                programa = p,
                                esActual = esActual,
                                avanceFraccion = if (esActual) avance(p, ahoraSegundos) else null,
                                xDp = dpDeEpoch(p.inicio, origenDelDia),
                                anchoDp = anchoDp(p),
                                esPrimero = i == 0,
                                chipsFocus = chipsFocus,
                                onClick = { if (esActual) onVerCanal(canal) else onDetalle(p) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Bloque gris "Cargando programación…" para una fila sin EPG todavía. Enfocable, no accionable. */
@Composable
private fun TvBloqueCargando(chipsFocus: FocusRequester) {
    Box(
        modifier = Modifier
            .padding(2.dp)
            .width(220.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .background(ArkivSurface)
            .dpadEscapaAChips(esPrimero = true, chipsFocus = chipsFocus)
            .focusable()
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text("Cargando programación…", style = MaterialTheme.typography.labelSmall, color = ArkivTextSecondary)
    }
}

/** Bloque para un canal con EPG cargada pero sin programas (día vacío). Estático, como el esqueleto. */
@Composable
private fun TvBloqueVacio(onClick: () -> Unit, chipsFocus: FocusRequester) {
    Box(
        modifier = Modifier
            .padding(2.dp)
            .width(220.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .background(ArkivSurface)
            .dpadEscapaAChips(esPrimero = true, chipsFocus = chipsFocus)
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text("Sin programación disponible", style = MaterialTheme.typography.labelSmall, color = ArkivTextSecondary)
    }
}

/** Un bloque de programa en el timeline: ancho proporcional a su duración, posición absoluta por epoch. */
@Composable
private fun TvBloquePrograma(
    programa: LiveProgram,
    esActual: Boolean,
    avanceFraccion: Float?,
    xDp: Float,
    anchoDp: Float,
    esPrimero: Boolean,
    chipsFocus: FocusRequester,
    onClick: () -> Unit,
) {
    var enfocado by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .offset(x = xDp.dp)
            .width(anchoDp.dp)
            .fillMaxHeight()
            .padding(2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (esActual) ArkivRed.copy(alpha = 0.28f) else ArkivSurfaceHigh)
            .border(
                width = if (enfocado) 3.dp else 0.dp,
                color = if (enfocado) Color.White else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
            )
            .onFocusChanged { enfocado = it.isFocused }
            .dpadEscapaAChips(esPrimero = esPrimero, chipsFocus = chipsFocus)
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column {
            Text(
                horaDe(programa.inicio),
                style = MaterialTheme.typography.labelSmall,
                color = if (esActual) Color.White else ArkivTextSecondary,
                maxLines = 1,
            )
            Text(
                programa.titulo,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (esActual) FontWeight.Bold else FontWeight.Normal,
            )
        }
        if (avanceFraccion != null) {
            Box(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp)
                    .background(Color(0x33FFFFFF)),
            ) {
                Box(modifier = Modifier.fillMaxWidth(avanceFraccion.coerceIn(0f, 1f)).fillMaxSize().background(ArkivRed))
            }
        }
    }
}

/**
 * Escape determinístico del borde izquierdo del timeline hacia los chips de categoría.
 *
 * Sin esto, al presionar Izquierda en el primer bloque de una fila (no hay nada más a la
 * izquierda dentro de ese `Row`/`Box`), la búsqueda de foco 2D de Compose puede saltar a
 * cualquier lado -- la fila de arriba, la de abajo, o quedarse sin moverse sin avisar. Es el mismo
 * PROBLEMA que resuelve `dpadFocusEscape` (`TvComponents.kt:55`) para Arriba/Abajo saliendo de un
 * `TextField`, pero esa función no sirve tal cual acá porque solo intercepta esas dos teclas;
 * este es el mismo patrón (`onPreviewKeyEvent` + mover el foco a mano) aplicado a Izquierda, con
 * un destino EXPLÍCITO (el primer chip) en vez de una búsqueda genérica -- más predecible que
 * `moveFocus`, que dependería de la geometría exacta de la pantalla.
 */
private fun Modifier.dpadEscapaAChips(esPrimero: Boolean, chipsFocus: FocusRequester): Modifier =
    if (!esPrimero) this else onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
            runCatching { chipsFocus.requestFocus() }.isSuccess
        } else {
            false
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

/**
 * Ficha de un programa que NO está en curso: puede ser futuro (todavía no empezó) o ya terminado
 * (más temprano en el día) -- [TvGuiaFilaCanal] llama acá para cualquiera de los dos, porque el
 * único bloque que abre directo con `onVerCanal` es el que está EN CURSO. El botón es el mismo,
 * "Ver canal ahora" (sin grabar ni recordatorio -- no existen, ver brief), pero el subtítulo
 * distingue "Ya terminó" de "Todavía no empezó" para que tocar un programa pasado no se lea igual
 * que tocar uno futuro (hallazgo de revisión).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvProgramaDialog(
    canal: LiveChannel,
    programa: LiveProgram,
    ahoraSegundos: Long,
    onVerAhora: () -> Unit,
    onDismiss: () -> Unit,
) {
    // `esActual` ya se filtra en TvGuiaFilaCanal (ese caso llama a onVerCanal directo, sin pasar
    // por acá), así que en la práctica es siempre uno de estos dos -- el `null` queda solo como
    // red de seguridad si algún día cambia esa condición.
    val estadoPrograma = when {
        programa.fin <= ahoraSegundos -> "Ya terminó"
        programa.inicio > ahoraSegundos -> "Todavía no empezó"
        else -> null
    }
    // Diálogo manual (Dialog + Column estilizada), no material3.AlertDialog: mismo patrón que
    // TvModoDeSerieDialog (TvSearchScreen.kt) y TvLibraryItemDialog (TvLibraryScreen.kt) -- el
    // resto de la app de TV no usa AlertDialog, y mezclar los dos looks se nota.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { focus.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.width(520.dp).clip(RoundedCornerShape(16.dp))
                .background(ArkivSurfaceHigh).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                programa.titulo,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${canal.nombre} · ${horaDe(programa.inicio)} - ${horaDe(programa.fin)}" +
                    (estadoPrograma?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = ArkivTextSecondary,
            )
            if (programa.sinopsis.isNotBlank()) {
                Text(
                    programa.sinopsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = onVerAhora,
                colors = arkivTvButtonColors(),
                border = arkivTvButtonBorder(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(focus),
            ) { Text("Ver canal ahora") }
            // Sin grabar ni recordatorio -- no existen (ver brief). Este cierre explícito es para
            // quien prefiera OK a Atrás, no reemplaza el BackHandler de TvLiveGuideContenido.
            Button(
                onClick = onDismiss,
                colors = arkivTvButtonColors(),
                border = arkivTvButtonBorder(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cerrar") }
        }
    }
}

/** Mensaje centrado simple, con reintentar opcional -- para "sin canales"/"cargando"/error. */
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
