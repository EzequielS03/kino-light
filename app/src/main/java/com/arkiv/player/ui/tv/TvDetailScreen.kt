package com.arkiv.player.ui.tv

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arkiv.player.data.model.Episode
import com.arkiv.player.miniaturas.EleccionDeMiniatura
import com.arkiv.player.ui.detail.DetailViewModel
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * Detalle de un ítem, estilo Prime Video: backdrop a pantalla completa con degradado, info
 * (título/cantidad de episodios/descripción/reproducir) sobre la izquierda, y abajo un carrusel
 * horizontal de episodios — el mismo [TvEpisodeChip] que usa el overlay de pausa del player.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvDetailScreen(
    /** Group key (`tv:46260`) or a raw identifier (an item that isn't grouped yet, or "Continue watching"). */
    groupKey: String,
    onPlayEpisode: (String) -> Unit,
) {
    val graph = rememberGraph()
    val vm: DetailViewModel = viewModel(
        factory = viewModelFactory { initializer { DetailViewModel(graph.repository, groupKey) } },
    )
    val sources by vm.sources.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val data = detail
    if (data == null) {
        // Antes esto era `?: return`: pantalla en negro sin ningún aviso, tanto mientras el
        // detalle está cargando como cuando la llave de grupo dejó de existir (ver el bug de
        // LibraryGrouping.resolveMembers/observeGroupMembers — un grupo que se movía de llave
        // bajo el usuario y esto no avisaba, se veía IDÉNTICO a un crash). Un mensaje simple
        // alcanza: no hace falta distinguir "todavía cargando" de "no se encontró".
        Box(Modifier.fillMaxSize().background(ArkivBlack), contentAlignment = Alignment.Center) {
            Text(
                "No se pudo cargar este contenido",
                style = MaterialTheme.typography.titleMedium,
                color = ArkivTextSecondary,
            )
        }
        return
    }
    // Identifier REAL de la fuente que se está mostrando (no la llave de grupo de la ruta): lo
    // que trae `data` ya resolvió `groupKey` a un ítem concreto. Stills/títulos de TMDB y el
    // caché de capítulos enfocados se indexan por ese identifier, no por la llave.
    val identifier = data.identifier

    val playFR = remember { FocusRequester() }
    val resumeEpisodeFR = remember { FocusRequester() }
    // Ancla del primer chip de "Fuentes": sin esto, el salto directo Reproducir<->capítulo
    // resumible (de abajo) se saltaba la fila entera y la dejaba inalcanzable con el D-pad.
    val firstSourceFR = remember { FocusRequester() }
    val episodesListState = rememberLazyListState()

    // El carrusel abre posicionado en el capítulo que se venía viendo (el mismo que reproduce el
    // botón Reproducir). En series largas quedaba fuera de pantalla y había que buscarlo a mano.
    // Stills de TMDB por capítulo: se resuelven una vez y quedan cacheados en la base; Coil
    // se encarga del caché de las imágenes en disco.
    val stills by graph.repository.observeEpisodeStills(identifier)
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    // Frames capturados durante la reproducción: la escena real del capítulo, cuando existe le
    // gana al still de TMDB (ver EleccionDeMiniatura). Solo tiene entrada si el capítulo se
    // empezó a ver, así que "gana solo en lo empezado" sale solo de que la clave no esté.
    val frames by graph.repository.observeEpisodeFrames(identifier)
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    // Títulos reales del capítulo (TMDB). El nombre del archivo suele ser inútil ("s01e03"), y en
    // el hero —que es texto grande— se nota mucho más que en la lista.
    val episodeTitles by graph.repository.observeEpisodeTitles(identifier)
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    // Sinopsis por capítulo (TMDB), para el bloque de descripción de más abajo cuando hay uno
    // enfocado en el carrusel.
    val episodeOverviews by graph.repository.observeEpisodeOverviews(identifier)
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    LaunchedEffect(identifier) {
        // Antes de pedir stills: un ítem de Magis guardado sin `tmdbId` no tiene con qué pedirlos,
        // y `ensureEpisodeStills` se iría en su primera línea. Esto le pregunta al gateway una sola
        // vez (se salta solo si ya tiene identidad). Ver [repararIdentidadDeMagis].
        com.arkiv.player.data.gateway.repararIdentidadDeMagis(
            graph.repository, graph.fuenteDeContenido, identifier,
        )
        runCatching { graph.repository.ensureEpisodeStills(identifier) }
    }

    // Capítulo enfocado en el carrusel: el fondo y los textos de arriba lo siguen, igual que el
    // hero del Home sigue a la card enfocada. Null = foco fuera del carrusel (p. ej. en
    // "Reproducir"), y entonces se muestra la info de la serie. También se resetea al cambiar de
    // fuente (chip de "Fuentes"): el capítulo enfocado pertenece a la lista vieja.
    var focusedEpisode by remember(identifier) { mutableStateOf<Episode?>(null) }

    val resumeId = data.resumeEpisode?.id
    // Reposiciona el carrusel SOLO al cambiar de fuente (chip de "Fuentes"), no en cada cambio
    // de `resumeId`. `episodesListState` no lleva `key` por `identifier`, así que sobrevive el
    // cambio de fuente; sin este reset el carrusel se quedaba scrolleado al offset de la fuente
    // vieja cuando el capítulo a resumir de la fuente nueva caía en el índice 0 — y con eso el
    // chip resumible (y `resumeEpisodeFR`, que ancla el foco desde el primer chip de fuentes)
    // fuera de la ventana que compone el LazyRow.
    //
    // OJO: la key es `identifier` solo, NO `resumeId`. `resumeId` también cambia dentro de la
    // MISMA fuente cuando el capítulo en curso pasa el 60% y `savePlayback` lo marca visto
    // (ArkivRepository.resumeEpisode pasa a ofrecer el SIGUIENTE capítulo): ese es el flujo más
    // común de volver al detalle, y si el effect corriera con esa key el carrusel le pegaba un
    // salto lejos de donde estaba el usuario cada vez que terminaba algo. Al depender solo de
    // `identifier`, este LaunchedEffect no se reinicia en ese caso — seguimos leyendo `data` y
    // `resumeId` "de tras el cierre" de la composición donde `identifier` cambió, que es
    // exactamente la fuente nueva recién elegida.
    LaunchedEffect(identifier) {
        val idx = data.episodes.indexOfFirst { it.id == resumeId }
        episodesListState.scrollToItem(idx.coerceAtLeast(0))
    }

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        // El fondo sigue al capítulo enfocado. Cae al backdrop de la serie cuando ese capítulo no
        // tiene still (TMDB no siempre los trae) o cuando el foco no está en el carrusel.
        // El hero describe SIEMPRE lo que va a pasar si apretás el botón: con el foco en el
        // carrusel, el capítulo enfocado; con el foco fuera (en "Reproducir"), el capítulo que ESE
        // botón reanuda. Antes, sacar el foco del carrusel caía a la info de la SERIE y se veía
        // incoherente: el botón decía "Reproducir T1 · E8" mientras el fondo cambiaba de imagen y
        // la descripción del capítulo desaparecía.
        //
        // Esto NO contradice el `focusedEpisode = null` del botón, lo completa: ese null está para
        // que no quede describiendo el último capítulo que recorriste (que no es el que se
        // reproduce). El respaldo pone en su lugar el que SÍ se reproduce.
        //
        // Solo en series: en una película `resumeEpisode` es el único "capítulo", y describirla
        // como capítulo perdería la sinopsis y la etiqueta de "Película".
        val focused = focusedEpisode ?: data.resumeEpisode?.takeIf { data.episodes.size > 1 }
        val heroImage = focused?.let { ep ->
            // El frame capturado manda sobre el still de TMDB. El thumb de archive.org que iba
            // después se borró en la poda de esta rama junto con esa fuente.
            EleccionDeMiniatura.elegir(
                frames[ep.id],
                stills[ep.id],
                null,
            )
        } ?: data.thumbnailUrl
        // Crossfade: sin esto, recorrer el carrusel con el D-pad hace parpadear el fondo entero en
        // cada chip. Con el fundido el cambio se lee como continuo.
        Crossfade(targetState = heroImage, label = "hero") { img ->
            AsyncImage(
                model = img,
                contentDescription = data.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Degradado horizontal: negro a la izquierda para leer el texto (igual que el hero del Home).
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(ArkivBlack, ArkivBlack, ArkivBlack.copy(alpha = 0.15f), Color.Transparent)),
            ),
        )
        // Degradado vertical: negro abajo para fundir con el carrusel.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, ArkivBlack.copy(alpha = 0.4f), ArkivBlack)),
            ),
        )

        Column(Modifier.fillMaxSize()) {
            // --- Info (título, cantidad, descripción, reproducir) ---
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 48.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                // Con un capítulo enfocado el hero pasa a describir ESE capítulo; el nombre de la
                // serie baja a la línea de arriba para no perder el contexto de dónde estás.
                if (focused != null) {
                    Text(
                        data.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = ArkivTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    focused?.let { episodeTitles[it.id] ?: it.displayName } ?: data.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (focused != null) Modifier.padding(top = 2.dp) else Modifier,
                )
                Text(
                    when {
                        focused != null -> episodeMeta(focused)
                        data.episodes.size > 1 -> com.arkiv.player.ui.EtiquetaDeCapitulo.avance(data, "episodios")
                        else -> "Película"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArkivTextSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                // Con capítulo enfocado, SU sinopsis (TMDB, guardada por Task 5) si la hay; si no
                // hay, nada — la de la serie no describe ESE capítulo puntual. Sin capítulo
                // enfocado, la sinopsis de la serie, como siempre.
                (if (focused != null) episodeOverviews[focused.id] else data.description)
                    ?.takeIf { it.isNotBlank() }?.let { desc ->
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp).widthIn(max = 640.dp),
                    )
                }
                data.resumeEpisode?.let { resume ->
                    Button(
                        onClick = { onPlayEpisode(resume.id) },
                        colors = arkivTvButtonColors(),
                        border = arkivTvButtonBorder(),
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .focusRequester(playFR)
                            // Al volver arriba desde el carrusel, el hero vuelve a la serie: si
                            // quedara el último capítulo enfocado, el botón "Reproducir" (que
                            // reanuda otro) estaría describiendo algo que no va a reproducir.
                            .onFocusChanged { if (it.isFocused) focusedEpisode = null }
                            // Si hay selector de fuente, bajar cae ahí primero; si no, directo al
                            // capítulo resumible (como antes). Sin este condicional el salto
                            // explícito se saltaba la fila de "Fuentes" enterita.
                            .focusProperties { down = if (sources.size > 1) firstSourceFR else resumeEpisodeFR },
                    ) {
                        Text("▶  ${com.arkiv.player.ui.EtiquetaDeCapitulo.botonReproducir(data)}")
                    }
                }
            }

            // --- Selector de fuente: solo aparece si la misma serie entró por más de una vía ---
            if (sources.size > 1) {
                Column(modifier = Modifier.padding(bottom = 16.dp)) {
                    Text(
                        "Fuentes",
                        style = MaterialTheme.typography.titleSmall,
                        color = ArkivTextSecondary,
                        modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(sources, key = { _, it -> it.identifier }) { index, src ->
                            // Source name + how many chapters it contributes: that's what lets you
                            // decide (e.g. "magis · 300 ep." vs "ditu · 267 ep.", or a legacy
                            // "web"/"torrent" source still saved in the library from before this
                            // branch's pruning).
                            TvSourceChip(
                                label = "${src.source} · ${src.episodeCount} ep.",
                                selected = src.identifier == selectedId,
                                onClick = { vm.selectSource(src.identifier) },
                                // Solo el primer chip ancla el salto explícito Reproducir<->carrusel:
                                // es al que aterrizan esos atajos, así que tiene que poder
                                // devolverlos a ambos lados.
                                modifier = if (index == 0) {
                                    Modifier.focusRequester(firstSourceFR)
                                        .focusProperties {
                                            // playFR y resumeEpisodeFR solo tienen nodo adjunto
                                            // cuando la fuente actual tiene un capítulo para
                                            // resumir (el botón "Reproducir" y el chip resumible
                                            // se renderizan condicionados a eso). Una fuente recién
                                            // agregada con 0 episodios (fetch fallido) deja ambos
                                            // sin adjuntar: seguir apuntándoles ahí hace que Compose
                                            // tire IllegalStateException al mover el foco. Con
                                            // FocusRequester.Default el D-pad usa el algoritmo por
                                            // defecto en vez de crashear.
                                            up = if (data.resumeEpisode != null) playFR else FocusRequester.Default
                                            down = if (data.resumeEpisode != null) resumeEpisodeFR else FocusRequester.Default
                                        }
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }
                }
            }

            // --- Carrusel de episodios (mismo componente que el overlay de pausa del player) ---
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                Text(
                    if (data.episodes.size > 1) "Episodios" else "Detalles",
                    style = MaterialTheme.typography.titleSmall,
                    color = ArkivTextSecondary,
                    modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
                )
                LazyRow(
                    state = episodesListState,
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(data.episodes, key = { it.id }) { ep ->
                        val isResume = ep.id == resumeId
                        TvEpisodeChip(
                            episode = ep,
                            isCurrent = data.inProgressEpisode?.id == ep.id,
                            progress = data.progress[ep.id],
                            // Same order as the hero: frame -> TMDB still. The archive.org fallback
                            // that used to live inside TvEpisodeChip after these two was removed
                            // with the rest of that source; with neither of these, the chip just
                            // shows a plain black background (see TvEpisodeChip's own comment).
                            stillUrl = EleccionDeMiniatura.elegir(frames[ep.id], stills[ep.id]),
                            onClick = { onPlayEpisode(ep.id) },
                            onFocus = { focusedEpisode = ep },
                            modifier = Modifier.then(
                                if (isResume) {
                                    // Simétrico al `down` de Reproducir: si hay selector de fuente,
                                    // subir cae ahí; si no, directo a Reproducir (como antes).
                                    Modifier.focusRequester(resumeEpisodeFR)
                                        .focusProperties { up = if (sources.size > 1) firstSourceFR else playFR }
                                } else {
                                    Modifier
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Línea de datos del capítulo enfocado: "T1 · E3 · 24 min".
 *
 * La numeración vive en [com.arkiv.player.ui.EtiquetaDeCapitulo] (compartida con el detalle del
 * celu); acá solo se le suman los minutos, si se conoce la duración.
 */
private fun episodeMeta(ep: Episode): String {
    val minutos = (ep.durationSeconds / 60).toInt()
    val numero = com.arkiv.player.ui.EtiquetaDeCapitulo.numero(ep)
    return if (minutos > 0) "$numero · $minutos min" else numero
}
