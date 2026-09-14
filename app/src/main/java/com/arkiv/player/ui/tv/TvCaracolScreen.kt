package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arkiv.player.data.ditu.DituChannel
import com.arkiv.player.data.ditu.DituFuente
import com.arkiv.player.data.ditu.DituItem
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.playback.DituLive
import com.arkiv.player.ui.catalog.ArkivCaracolVerde
import com.arkiv.player.ui.catalog.CaracolCatalog
import com.arkiv.player.ui.catalog.EstadoDeCanales
import com.arkiv.player.ui.catalog.PlaySource
import com.arkiv.player.ui.catalog.esSerie
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.search.PlaybackResult
import com.arkiv.player.ui.search.SearchPlayback
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * La sección de Caracol en el televisor: su catálogo y sus canales en vivo.
 *
 * El catálogo lo guarda [DituFuente.fullCatalog] 6 h; "Recargar" lo pide aunque no haya
 * vencido, para cuando Caracol agrega algo. Los canales se piden cada vez que se entra.
 *
 * Abrir un título va por el MISMO camino que la búsqueda (`playResult` de [TvSearchScreen]), no por
 * uno propio: [DituFuente.resultFrom] lo vuelve el mismo resultado que da la búsqueda, una película
 * se guarda y se abre con [SearchPlayback.playDitu], y una serie abre [TvCapitulosDeCaracol], que al
 * tocar un capítulo guarda la serie entera. Los dos guardan con id `ditu:`: la película por
 * `ArkivRepository.addDituSource`, la serie por `ArkivRepository.addDituSeason`.
 *
 * Un canal en vivo no pasa por la biblioteca: viaja por [DituLive].
 */
@Composable
internal fun TvCaracolScreen(onPlay: (episodeId: String) -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val playback = remember { SearchPlayback(graph) }
    var titulos by remember { mutableStateOf<List<DituItem>>(emptyList()) }
    var canales by remember { mutableStateOf<EstadoDeCanales>(EstadoDeCanales.Cargando) }
    var cargando by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var recargas by remember { mutableStateOf(0) }
    // La serie abierta: sus capítulos tapan la sección hasta que se elige uno o se vuelve con Atrás.
    var serieAbierta by remember { mutableStateOf<GatewayResult?>(null) }
    var preparando by remember { mutableStateOf(false) }
    // Lo que pasó recién (no se pudo abrir, no se pudo recargar). Se borra solo, como el aviso de
    // [TvSeccionesDeCatalogo].
    var aviso by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(aviso) {
        if (aviso == null) return@LaunchedEffect
        delay(3500)
        aviso = null
    }

    LaunchedEffect(recargas) {
        cargando = true
        // Cada cosa falla sola: que no haya canales no puede dejar la pantalla sin catálogo.
        runCatching { graph.dituFuente.fullCatalog(force = recargas > 0) }
            .onSuccess { titulos = it; error = null }
            .onFailure {
                // El detalle va al log; en pantalla, en palabras de persona.
                android.util.Log.w("TvCaracol", "catalog failed to load", it)
                error = com.arkiv.player.data.ditu.CaracolFailure.onLoadCatalog(it)
                aviso = error
            }
        // Si falla, se dice en la pestaña: no puede verse igual que "no hay canales".
        val resultadoDeCanales = runCatching { graph.dituFuente.channels() }
        resultadoDeCanales.exceptionOrNull()?.let { android.util.Log.w("TvCaracol", "channels failed to load", it) }
        canales = EstadoDeCanales.de(resultadoDeCanales)
        cargando = false
    }

    fun alTerminar(resultado: PlaybackResult) {
        preparando = false
        when (resultado) {
            is PlaybackResult.Ready -> onPlay(resultado.episodeId)
            is PlaybackResult.Failed -> aviso = resultado.message
        }
    }

    // Lo mismo que hace la búsqueda con un resultado de Caracol.
    fun abrirTitulo(item: DituItem) {
        if (preparando) return
        val fuente = PlaySource.Ditu(DituFuente.resultFrom(item))
        if (fuente.esSerie()) {
            serieAbierta = fuente.result
            return
        }
        preparando = true
        aviso = null
        scope.launch { alTerminar(playback.playDitu(fuente.result)) }
    }

    BackHandler(enabled = serieAbierta != null) { serieAbierta = null }

    val serie = serieAbierta
    if (serie != null) {
        Box(Modifier.fillMaxSize().background(ArkivBlack)) {
            TvCapitulosDeCaracol(
                serie = serie,
                posterUrl = serie.extra["poster"].orEmpty(),
                preparing = preparando,
                alElegir = { guardar ->
                    serieAbierta = null
                    preparando = true
                    aviso = null
                    scope.launch { alTerminar(guardar()) }
                },
            )
        }
        return
    }

    TvCaracolContenido(
        titulos = titulos,
        canales = canales,
        cargando = cargando,
        error = error,
        aviso = if (preparando) "Preparando…" else aviso,
        alRecargar = { recargas++ },
        alAbrirTitulo = { abrirTitulo(it) },
        alAbrirCanal = { canal -> onPlay(DituLive.leave(canal)) },
    )
}

/**
 * La sección ya con sus datos. Su molde es [TvSeccionesDeCatalogo], y usa sus mismas piezas: [TvTab]
 * arriba, filas horizontales con [PivotoDeTv], la columna con [TraerConScrollMinimo], el nombre de lo
 * enfocado en un bloque de alto fijo, y la zona de filas medida en dos filas enteras.
 *
 * Los títulos van en [TvPosterCard] y no en la [TvLandscapeCard] del molde: el arte que trae Caracol
 * es vertical (`DituCatalog.POSTER`). Los canales van en [TvLandscapeCard], con su logo.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvCaracolContenido(
    titulos: List<DituItem>,
    canales: EstadoDeCanales,
    cargando: Boolean,
    error: String?,
    aviso: String?,
    alRecargar: () -> Unit,
    alAbrirTitulo: (DituItem) -> Unit,
    alAbrirCanal: (DituChannel) -> Unit,
) {
    var enVivo by remember { mutableStateOf(false) }
    var enfocado by remember { mutableStateOf<Enfocado?>(null) }
    // Al cambiar de pestaña, lo enfocado ya no está en pantalla.
    LaunchedEffect(enVivo) { enfocado = null }

    val filas = remember(titulos) { filasDeCaracol(titulos) }
    val listaDeCanales = (canales as? EstadoDeCanales.Listos)?.canales.orEmpty()

    // Enganche al borde de fila, tal cual del molde (ver su comentario): un ítem de la lista es una
    // fila enfocable, así que al frenar el scroll se redondea a la frontera más cercana.
    val estadoDeLasFilas = rememberLazyListState()
    LaunchedEffect(estadoDeLasFilas) {
        snapshotFlow { estadoDeLasFilas.isScrollInProgress }.collect { enMovimiento ->
            if (enMovimiento) return@collect
            val corrimiento = estadoDeLasFilas.firstVisibleItemScrollOffset
            if (corrimiento == 0) return@collect
            val alto = estadoDeLasFilas.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: return@collect
            val destino = estadoDeLasFilas.firstVisibleItemIndex + if (corrimiento > alto / 2) 1 else 0
            runCatching { estadoDeLasFilas.animateScrollToItem(destino) }
        }
    }

    val focoPrimerTab = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(20) {
            if (runCatching { focoPrimerTab.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    val altoDePoster = 130.dp
    val altoDeCanal = 92.dp
    val gapEntreFilas = 8.dp
    val rowsTopPad = 6.dp
    // +20: la fila lleva 10 dp de aire arriba y abajo para que el zoom del foco no se recorte.
    val altoDeFila = altoDePoster + 20.dp
    // Igual que el molde: la zona mide exactamente dos filas y el resto se lo queda el encabezado.
    val altoDeLaZona = (altoDeFila + gapEntreFilas) * 2 + rowsTopPad

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
            Column(Modifier.fillMaxWidth().weight(1f)) {
                // Las pestañas se pintan SIEMPRE, aunque la lista esté cargando o haya fallado: si
                // no, no habría con qué cambiar de pestaña ni recargar.
                Row(
                    Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Caracol", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Spacer(Modifier.weight(1f))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item {
                            TvTab(
                                etiqueta = "Catálogo",
                                seleccionada = !enVivo,
                                onClick = { enVivo = false },
                                modifier = Modifier.focusRequester(focoPrimerTab),
                            )
                        }
                        item { TvTab(etiqueta = "En vivo", seleccionada = enVivo, onClick = { enVivo = true }) }
                        item { TvTab(etiqueta = "Recargar", seleccionada = false, onClick = alRecargar) }
                    }
                }

                val vacia = if (enVivo) listaDeCanales.isEmpty() else titulos.isEmpty()
                if (vacia) {
                    Mensaje(
                        when {
                            enVivo -> when (canales) {
                                EstadoDeCanales.Cargando, is EstadoDeCanales.Listos -> "Cargando…"
                                EstadoDeCanales.Vacio -> "Caracol no tiene canales en vivo para mostrar."
                                // Falló: se dice en palabras de persona (ver EstadoDeCanales), y "Recargar" lo reintenta.
                                is EstadoDeCanales.Fallo -> "${canales.mensaje}\nPrueba otra vez con «Recargar»."
                            }
                            cargando -> "Cargando…"
                            else -> error ?: "Caracol no devolvió títulos."
                        },
                    )
                } else {
                    TextoDelHero(enfocado, aviso)
                }
            }

            if (enVivo && listaDeCanales.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().height(altoDeLaZona).padding(top = rowsTopPad)) {
                    CompositionLocalProvider(LocalBringIntoViewSpec provides PivotoDeTv) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(listaDeCanales, key = { it.channelId }) { canal ->
                                TvLandscapeCard(
                                    title = canal.name,
                                    imageUrl = canal.logoUrl,
                                    cardHeight = altoDeCanal,
                                    onFocus = { enfocado = Enfocado("En vivo", canal.name, "") },
                                    onClick = { alAbrirCanal(canal) },
                                )
                            }
                        }
                    }
                }
            } else if (!enVivo && filas.isNotEmpty()) {
                // El pivote VERTICAL es el de scroll mínimo, como en el molde: con el del 30 % una
                // fila de este alto quedaría cortada arriba.
                CompositionLocalProvider(LocalBringIntoViewSpec provides TraerConScrollMinimo) {
                    LazyColumn(
                        state = estadoDeLasFilas,
                        modifier = Modifier.fillMaxWidth().height(altoDeLaZona).padding(top = rowsTopPad),
                    ) {
                        items(filas, key = { it.clave }) { fila ->
                            Column {
                                FilaDeTitulos(
                                    titulos = fila.titulos,
                                    altoDePoster = altoDePoster,
                                    alAbrir = alAbrirTitulo,
                                    alEnfocar = { enfocado = Enfocado(fila.seccion, it.title, it.year) },
                                )
                                Spacer(Modifier.height(gapEntreFilas))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Una fila de pósters, con el mismo pivote que las del molde. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilaDeTitulos(
    titulos: List<DituItem>,
    altoDePoster: Dp,
    alAbrir: (DituItem) -> Unit,
    alEnfocar: (DituItem) -> Unit,
) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides PivotoDeTv) {
        LazyRow(
            // El `vertical` es el del molde: la tarjeta enfocada escala a 1.08 ([TvPosterCard]) y en
            // una fila del alto justo se recortaría el borde del foco.
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(titulos, key = { it.ref() }) { item ->
                TvPosterCard(
                    title = item.title,
                    posterUrl = item.posterUrl,
                    cardHeight = altoDePoster,
                    // El nombre va arriba, en el bloque de lo enfocado: bajo la tarjeta no entra.
                    showTitle = false,
                    onFocus = { alEnfocar(item) },
                    onClick = { alAbrir(item) },
                )
            }
        }
    }
}

/**
 * El nombre de lo enfocado, o el aviso de lo que acaba de pasar, que lo pisa mientras dura. Alto fijo
 * como en el molde: si apareciera y desapareciera, las filas de abajo saltarían.
 */
@Composable
private fun TextoDelHero(enfocado: Enfocado?, aviso: String?) {
    Column(Modifier.fillMaxWidth(0.55f).height(96.dp).padding(start = 48.dp, bottom = 12.dp)) {
        if (aviso != null) {
            Text(
                aviso,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            return@Column
        }
        val e = enfocado ?: return@Column
        Text(
            e.seccion,
            style = MaterialTheme.typography.labelLarge,
            color = ArkivCaracolVerde,
            maxLines = 1,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        Text(
            e.titulo,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (e.detalle.isNotBlank()) {
            Text(
                e.detalle,
                style = MaterialTheme.typography.titleSmall,
                color = ArkivTextSecondary,
                maxLines = 1,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun Mensaje(texto: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            texto,
            style = MaterialTheme.typography.bodyMedium,
            color = ArkivTextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** Lo enfocado, para el bloque de arriba: de qué fila es, cómo se llama y su año (o nada). */
private data class Enfocado(val seccion: String, val titulo: String, val detalle: String)

/** Una fila del catálogo: el ítem del `LazyColumn`. */
private data class FilaDeCaracol(val clave: String, val seccion: String, val titulos: List<DituItem>)

/**
 * Las series primero y después las películas, de a [TITULOS_POR_FILA]. Son unos 330 títulos (ver
 * [com.arkiv.player.data.ditu.DituCatalog]), y una fila con todos los de un tipo se recorre tarjeta
 * por tarjeta con el D-pad; partidas, se baja entre filas, que es el gesto del molde.
 *
 * El split en series/películas (sin repetidos, por [CaracolCatalog]) es compartido con la sección
 * del celular; el chunking en filas de a [TITULOS_POR_FILA] es solo de la fila horizontal del
 * televisor, así que se queda acá.
 */
private fun filasDeCaracol(titulos: List<DituItem>): List<FilaDeCaracol> {
    val catalogo = CaracolCatalog.split(titulos)
    return listOf("Series" to catalogo.series, "Películas" to catalogo.movies).flatMap { (nombre, lista) ->
        lista.chunked(TITULOS_POR_FILA).mapIndexed { i, fila -> FilaDeCaracol("$nombre:$i", nombre, fila) }
    }
}

private const val TITULOS_POR_FILA = 20
