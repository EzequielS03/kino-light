package com.arkiv.player.ui.live

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.arkiv.player.ui.columnasDeGrilla
import com.arkiv.player.ui.esTabletHorizontal
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveProgram
import com.arkiv.player.playback.PlayerSource
import com.arkiv.player.remote.PlayKind
import com.arkiv.player.remote.PlayPayload
import com.arkiv.player.remote.tvTargetAvailable
import com.arkiv.player.ui.components.EmptyState
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.launch

/** Vistas que no vienen del gateway: se arman con datos locales (Room), no con [LiveViewModel.elegirCategoria]. */
private enum class VistaLocal { NINGUNA, RECIENTES }

/**
 * Pestaña "En vivo": grilla de canales con buscador, categorías (con Favoritos/Recientes
 * primero) y "ahora en pantalla". Es la primera pieza de interfaz de la sección, así que cuida
 * los dos estados que importan: abre al instante con lo cacheado, y no deja un error crudo si el
 * gateway está lento o caído.
 *
 * No exige cuenta de Magis vinculada: el catálogo de canales usa la sesión anónima del gateway
 * (por número de serie del dispositivo, igual que el CLI de magia) cuando no hay cuenta
 * vinculada -- ver `MagisSession` en el gateway. Si el usuario SÍ tiene cuenta vinculada, el
 * GATEWAY la resuelve solo, a partir de la sesión autenticada (ya no hace falta que el cliente
 * mande el accountId por cabecera -- eso permitía pedir con la cuenta de Magis de otra persona),
 * sin que esta pantalla tenga que saber nada al respecto.
 *
 * [onAbrirCanal] recibe el código del canal tocado; el llamador (`ArkivRoot`) decide qué hacer con
 * ese código -- hoy, navegar al reproductor en modo vivo (Tarea 14). Antes de invocarlo, `abrir()`
 * fija en [LiveZappingSource] la lista con la que se entró (para que el zapping del reproductor la
 * recorra), así que esta pantalla no necesita saber nada del reproductor.
 *
 * Tarea 15: si hay un TV pareado, tocar un canal abre el diálogo de destino (mismo patrón que
 * `playEpisode`/`playChoice` de VOD en `ArkivRoot`) en vez de reproducir directo -- "Este teléfono"
 * sigue llamando a [onAbrirCanal] igual que siempre. "En la TV" manda el comando remoto
 * `live:<code>`: el TV lo recibe y **resuelve el canal por su cuenta** contra su propio
 * [LiveController] (ver `ArkivTvRoot.incomingPlay`), así que nunca se le manda la URL del proxy
 * de este teléfono -- esa URL apunta a `127.0.0.1` DE ESTE aparato y no significa nada en el TV.
 * Sin TV pareada, tocar sigue abriendo directo (mismo camino rápido de siempre).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveScreen(
    onAbrirCanal: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm: LiveViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                LiveViewModel(
                    graph.liveApi, graph.database.liveFavoriteDao(),
                    graph.database.liveChannelCacheDao(),
                    // Se lee en CADA carga, no una vez: destrabar 18+ desde Ajustes tiene
                    // que verse al volver a entrar, sin reiniciar la app.
                    adultosDesbloqueado = { graph.deviceStore.adultosDesbloqueado() },
                )
            }
        },
    )
    val estado by vm.estado.collectAsStateWithLifecycle()
    var vista by remember { mutableStateOf(VistaLocal.NINGUNA) }
    // rememberSaveable: el brief pide que el modo sobreviva a la rotación (cambio de configuración
    // recompone toda la pantalla desde cero, y con `remember` volvería siempre a la grilla).
    var modoGuia by rememberSaveable { mutableStateOf(false) }

    // Mismo cálculo que ArkivRoot.tvAvailable (ver su comentario): solo tiene sentido el diálogo de
    // destino si ESTE teléfono pareó una TV -- sin pareo no hay a quién mandarle el comando remoto.
    val tvLinked by graph.settings.tvLinked.collectAsStateWithLifecycle()
    val lanTvAvailable by graph.syncManager.tvAvailable.collectAsStateWithLifecycle()
    val pairedTvAvailable by graph.remoteController.tvPaired.collectAsStateWithLifecycle()
    val tvAvailable = tvTargetAvailable(tvLinked, lanTvAvailable, pairedTvAvailable)

    // Canal en espera de que el usuario elija destino -- null = sin diálogo abierto.
    var destino by remember { mutableStateOf<LiveChannel?>(null) }

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

    // La lista "con la que se entró" (categoría/favoritos, o recientes) -- Tarea 14: es la que el
    // zapping del reproductor recorre, no el catálogo completo. Se fija en LiveZappingSource ANTES
    // de abrir: una lista de LiveChannel no cruza bien la ruta de navegación (un String), ver el
    // KDoc de LiveZappingSource (LiveZapping.kt).
    val listaActiva = if (vista == VistaLocal.RECIENTES) filtrar(recientes, estado.busqueda) else estado.visibles
    fun abrirAca(canal: LiveChannel) {
        LiveZappingSource.lista = listaActiva
        onAbrirCanal(canal.code)
    }
    fun abrir(canal: LiveChannel) {
        if (tvAvailable) destino = canal else abrirAca(canal)
    }
    fun favorito(canal: LiveChannel) = vm.alternarFavorito(canal)

    /** "En la TV": manda `live:<code>` por el control remoto -- el TV resuelve por su cuenta. */
    fun enviarATv(canal: LiveChannel) {
        scope.launch {
            val id = "${PlayerSource.LIVE_PREFIX}${canal.code}"
            val ok = graph.remoteController.sendPlay(PlayPayload(PlayKind.LIVE, id, id))
            if (ok) {
                Toast.makeText(context, "Enviado a la TV", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "No se pudo conectar con la TV, lo abro acá", Toast.LENGTH_LONG).show()
                onAbrirCanal(canal.code)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { modoGuia = !modoGuia }) {
                Icon(
                    imageVector = if (modoGuia) Icons.Default.GridView else Icons.Default.ViewAgenda,
                    contentDescription = if (modoGuia) "Ver como grilla" else "Ver guía de programación",
                    tint = if (modoGuia) ArkivRed else ArkivTextSecondary,
                )
            }
        }

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
                } else if (modoGuia) {
                    LiveGuideList(visibles, estado.programacion, ::abrir, vm::pedirEpgDe, gridPadding)
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
            modoGuia -> LiveGuideList(estado.visibles, estado.programacion, ::abrir, vm::pedirEpgDe, gridPadding)
            else -> ChannelGrid(estado.visibles, estado.ahora, estado.favoritos, gridPadding, ::abrir, ::favorito)
        }
    }

    destino?.let { canal ->
        DestinoDialog(
            canal = canal,
            onEsteTelefono = { destino = null; abrirAca(canal) },
            onEnLaTv = { destino = null; enviarATv(canal) },
            onDismiss = { destino = null },
        )
    }
}

/**
 * "¿Dónde querés ver <canal>?" -- mismo patrón que el diálogo de destino de VOD (`playChoice` en
 * `ArkivRoot`), pero con una tercera opción propia del vivo.
 *
 * Este diálogo NO ofrece Chromecast (ni VOD lo ofrece en el suyo, por el mismo motivo -- ver el
 * comentario en `ArkivRoot`: "Chromecast/DLNA siguen disponibles dentro del player"): acá todavía
 * no hay reproductor local abierto -el usuario está eligiendo destino ANTES de reproducir nada-
 * así que no hay de dónde leer el códec del canal (`vlc.currentAudioFormat()`) para decidir si
 * hace falta transcodificar (ver `CastAudioSupport`/`CastTranscoder`, y el KDoc de
 * `castRequestFor` en `PlayerScreen`). El camino real es reproducir el canal ("Este teléfono") y
 * castear DESDE AHÍ con el botón de Chromecast del reproductor (Tarea 18), donde el canal ya está
 * sonando y esa lectura sí existe -- el reproductor ya trae Chromecast y DLNA una vez adentro, no
 * hace falta duplicarlos acá.
 */
@Composable
private fun DestinoDialog(
    canal: LiveChannel,
    onEsteTelefono: () -> Unit,
    onEnLaTv: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = ArkivSurface) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "¿Dónde querés ver ${canal.nombre}?",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                Button(
                    onClick = onEnLaTv,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ArkivRed, contentColor = Color.White),
                ) { Text("En la TV") }
                Button(
                    onClick = onEsteTelefono,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ArkivRed, contentColor = Color.White),
                ) { Text("Este teléfono") }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
            }
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
private fun ChannelGrid(
    canales: List<LiveChannel>,
    ahora: Map<String, LiveProgram?>,
    favoritos: Set<String>,
    contentPadding: PaddingValues,
    onAbrir: (LiveChannel) -> Unit,
    onFavorito: (LiveChannel) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columnasDeGrilla(2, esTabletHorizontal())),
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
        columns = GridCells.Fixed(columnasDeGrilla(2, esTabletHorizontal())),
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
