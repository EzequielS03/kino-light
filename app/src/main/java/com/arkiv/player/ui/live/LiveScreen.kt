package com.arkiv.player.ui.live

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveProgram
import com.arkiv.player.pocketbase.AccountState
import com.arkiv.player.ui.components.EmptyState
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.launch

/** Vistas que no vienen del gateway: se arman con datos locales (Room), no con [LiveViewModel.elegirCategoria]. */
private enum class VistaLocal { NINGUNA, RECIENTES }

/**
 * Pestaña "En vivo": grilla de canales con buscador, categorías (con Favoritos/Recientes
 * primero) y "ahora en pantalla". Es la primera pieza de interfaz de la sección, así que cuida
 * los tres estados que importan: abre al instante con lo cacheado, no se cae sin cuenta de Magis,
 * y no deja un error crudo si el gateway está lento o caído.
 *
 * El gate de Magis vive ACÁ, antes de construir [LiveViewModel] -- no adentro de [LiveContenido] --
 * a propósito: `viewModel(factory = ...)` dispara el `init` del ViewModel (que ya llama al
 * gateway) apenas se compone por primera vez. Si el gate estuviera después de esa llamada, el
 * "no se cae sin cuenta de Magis" sería solo visual: la pantalla de "Vinculá tu cuenta" se vería
 * bien, pero [LiveViewModel] ya habría hecho pedidos de red reales antes de que nadie confirmara
 * que hay con qué autenticarlos (medido en review). Con `viewModel()` fuera de la rama
 * `sinMagis`, [LiveContenido] -y por lo tanto el ViewModel- ni se compone mientras el gate esté
 * activo.
 *
 * [onAbrirCanal] recibe el código del canal tocado; hoy no hay reproductor en modo vivo (llega en
 * la Tarea 14: bandera `enVivo` + zapping en `PlayerViewModel`/`PlayerScreen`), así que el
 * llamador de esta pantalla decide qué hacer con ese código.
 */
@Composable
fun LiveScreen(
    onOpenSettings: () -> Unit,
    onAbrirCanal: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val graph = rememberGraph()
    val cuenta by graph.accountManager.state.collectAsStateWithLifecycle()
    // "Vinculado" es la única condición real: Anónimo o Conectado-sin-Magis se tratan igual, porque
    // el vivo depende de la cuenta de Magis, no de tener sesión en Arkiv.
    val sinMagis = (cuenta as? AccountState.Conectado)?.magisLinked != true

    // Por si el vínculo cambió del lado del servidor (otro dispositivo lo vinculó/desvinculó)
    // desde la última vez que se abrió esta pantalla -- mismo patrón que AccountSection. Corre
    // SIEMPRE (incluso con sinMagis == true): es lo único que puede sacarnos de ese estado.
    LaunchedEffect(Unit) { runCatching { graph.accountManager.refrescarMagis() } }

    if (sinMagis) {
        SinCuentaMagis(onOpenSettings, contentPadding)
    } else {
        LiveContenido(onAbrirCanal, contentPadding)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveContenido(
    onAbrirCanal: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val graph = rememberGraph()
    val vm: LiveViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                LiveViewModel(graph.liveApi, graph.database.liveFavoriteDao(), graph.database.liveChannelCacheDao())
            }
        },
    )
    val estado by vm.estado.collectAsStateWithLifecycle()
    var vista by remember { mutableStateOf(VistaLocal.NINGUNA) }

    // Recientes: no pasa por LiveViewModel.elegirCategoria (no es una categoría del portal), se lee
    // directo de Room. Sin numero/logo propios (Tarea 10 no los guarda para "recientes"), así que se
    // enriquecen con lo que ya esté cargado en `estado.canales`, si el canal aparece ahí.
    val recentDao = remember { graph.database.liveRecentDao() }
    val recientesCrudo by recentDao.flowUltimos().collectAsStateWithLifecycle(initialValue = emptyList())
    val recientes = remember(recientesCrudo, estado.canales) {
        recientesCrudo.map { r ->
            estado.canales.find { it.code == r.code }?.copy(nombre = r.nombre)
                ?: LiveChannel(r.code, r.nombre, 0, null)
        }
    }
    LaunchedEffect(recientes) {
        if (recientes.isNotEmpty()) vm.pedirEpgDe(recientes.map { it.code })
    }

    // Precalentar los favoritos (acotado): son los canales con más chance de abrirse a continuación,
    // y resolver cuesta ~3s (ver LiveController) -- que ya estén resueltos para cuando exista el
    // reproductor en vivo (Tarea 14) es gratis y best-effort (precalentar() nunca lanza).
    LaunchedEffect(estado.favoritos) {
        estado.favoritos.take(5).forEach { code -> launch { graph.liveController.precalentar(code) } }
    }

    fun abrir(canal: LiveChannel) = onAbrirCanal(canal.code)
    fun favorito(canal: LiveChannel) = vm.alternarFavorito(canal)

    Column(modifier = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        OutlinedTextField(
            value = estado.busqueda,
            onValueChange = vm::buscar,
            placeholder = { Text("Buscar por nombre o número…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (estado.busqueda.isNotEmpty()) {
                    IconButton(onClick = { vm.buscar("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Limpiar")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                CategoriaChip(
                    label = "Favoritos",
                    icon = Icons.Default.Star,
                    selected = vista == VistaLocal.NINGUNA && estado.categoriaActiva == CATEGORIA_FAVORITOS,
                    onClick = { vista = VistaLocal.NINGUNA; vm.elegirCategoria(CATEGORIA_FAVORITOS) },
                )
            }
            item {
                CategoriaChip(
                    label = "Recientes",
                    icon = Icons.Default.History,
                    selected = vista == VistaLocal.RECIENTES,
                    onClick = { vista = VistaLocal.RECIENTES },
                )
            }
            items(estado.categorias, key = { it.id }) { cat ->
                CategoriaChip(
                    label = cat.nombre,
                    icon = null,
                    selected = vista == VistaLocal.NINGUNA && estado.categoriaActiva == cat.id,
                    onClick = { vista = VistaLocal.NINGUNA; vm.elegirCategoria(cat.id) },
                )
            }
        }

        // Aviso fino de refresco: la grilla ya tiene algo pintado (caché o carga previa), así que
        // un spinner a pantalla completa sería peor que no decir nada -- solo una barrita arriba.
        if (estado.cargando && (vista == VistaLocal.NINGUNA && estado.canales.isNotEmpty())) {
            LinearProgressIndicator(color = ArkivRed, modifier = Modifier.fillMaxWidth())
        }

        val gridPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        )

        when {
            vista == VistaLocal.RECIENTES -> {
                val visibles = filtrar(recientes, estado.busqueda)
                if (visibles.isEmpty()) {
                    EmptyState(
                        "Sin canales recientes",
                        "Los canales que abras van a aparecer acá.",
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    ChannelGrid(visibles, estado.ahora, estado.favoritos, gridPadding, ::abrir, ::favorito)
                }
            }
            estado.error != null && estado.canales.isEmpty() -> {
                ErrorConReintento(estado.error!!) { vm.elegirCategoria(estado.categoriaActiva) }
            }
            estado.cargando && estado.canales.isEmpty() -> {
                PlaceholderGrid(gridPadding)
            }
            estado.visibles.isEmpty() -> {
                val (title, subtitle) = when {
                    estado.busqueda.isNotBlank() -> "Sin resultados" to "Probá con otro nombre o número de canal."
                    estado.categoriaActiva == CATEGORIA_FAVORITOS -> "Sin favoritos todavía" to
                        "Mantené pulsado un canal para agregarlo."
                    else -> "Sin canales" to "No encontramos canales en esta categoría."
                }
                EmptyState(title, subtitle, modifier = Modifier.fillMaxSize())
            }
            else -> ChannelGrid(estado.visibles, estado.ahora, estado.favoritos, gridPadding, ::abrir, ::favorito)
        }
    }
}

@Composable
private fun CategoriaChip(
    label: String,
    icon: ImageVector?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp)) } },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = ArkivRed,
            selectedLabelColor = Color.White,
            selectedLeadingIconColor = Color.White,
        ),
    )
}

@Composable
private fun ErrorConReintento(mensaje: String, onReintentar: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(mensaje, style = MaterialTheme.typography.bodyLarge, color = ArkivTextSecondary, textAlign = TextAlign.Center)
        Button(
            onClick = onReintentar,
            colors = ButtonDefaults.buttonColors(containerColor = ArkivRed, contentColor = Color.White),
            modifier = Modifier.padding(top = 16.dp),
        ) { Text("Reintentar") }
    }
}

@Composable
private fun SinCuentaMagis(onOpenSettings: () -> Unit, contentPadding: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(contentPadding).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.LiveTv,
            contentDescription = null,
            tint = ArkivTextSecondary,
            modifier = Modifier.size(48.dp),
        )
        Text(
            "Vinculá tu cuenta de Magis",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "El canal en vivo necesita una cuenta de Magis vinculada a tu cuenta de Arkiv. " +
                "Podés vincularla desde Ajustes.",
            style = MaterialTheme.typography.bodyLarge,
            color = ArkivTextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(
            onClick = onOpenSettings,
            colors = ButtonDefaults.buttonColors(containerColor = ArkivRed, contentColor = Color.White),
            modifier = Modifier.padding(top = 16.dp),
        ) { Text("Ir a Ajustes") }
    }
}

@Composable
private fun ChannelGrid(
    canales: List<LiveChannel>,
    ahora: Map<String, LiveProgram?>,
    favoritos: Set<String>,
    contentPadding: PaddingValues,
    onAbrir: (LiveChannel) -> Unit,
    onFavorito: (LiveChannel) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(canales, key = { it.code }) { canal ->
            ChannelCard(
                canal = canal,
                ahoraPrograma = ahora[canal.code],
                esFavorito = canal.code in favoritos,
                onClick = { onAbrir(canal) },
                onLongClick = { onFavorito(canal) },
            )
        }
    }
}

/** Grilla de placeholders mientras carga la primera vez (sin caché todavía que mostrar). */
@Composable
private fun PlaceholderGrid(contentPadding: PaddingValues) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(8) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ArkivSurfaceHigh),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelCard(
    canal: LiveChannel,
    ahoraPrograma: LiveProgram?,
    esFavorito: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(ArkivSurfaceHigh),
        ) {
            if (canal.logo != null) {
                AsyncImage(
                    model = canal.logo,
                    contentDescription = canal.nombre,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                )
            } else {
                // No está confirmado que el portal mande logos: sin uno, el mismo tratamiento visual
                // que CardPlaceholder (ui/tv/TvComponents.kt) -- degradado oscuro -- pero con el
                // número del canal en vez del ícono genérico, para que la tarjeta se vea deliberada
                // y no como un logo roto.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(listOf(Color(0xFF33333D), Color(0xFF17171C)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = canal.numero.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }
            if (esFavorito) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "Favorito",
                    tint = ArkivRed,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
                )
            }
        }
        Text(
            text = canal.nombre,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        // "Ahora en pantalla": nada si todavía no hay EPG para este canal (nunca un hueco fijo ni
        // un "cargando" parpadeante por tarjeta -- ver brief).
        if (ahoraPrograma != null) {
            Text(
                text = "Ahora: ${ahoraPrograma.titulo}",
                style = MaterialTheme.typography.bodySmall,
                color = ArkivTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x33FFFFFF)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progresoDePrograma(ahoraPrograma))
                        .fillMaxSize()
                        .background(ArkivRed),
                )
            }
        }
    }
}
