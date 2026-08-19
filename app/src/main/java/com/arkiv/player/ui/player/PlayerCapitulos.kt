package com.arkiv.player.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.model.Episode
import com.arkiv.player.ui.tv.TvEpisodeChip
import kotlinx.coroutines.delay

/**
 * Cuántos frames se reintenta enganchar el foco al chip actual. En TV de gama baja (Fire Stick) el
 * LazyRow recién revelado no está compuesto ni medido en el primer frame.
 */
private const val INTENTOS_DE_FOCO = 12
private const val ESPERA_ENTRE_INTENTOS_MS = 32L

/**
 * Carrusel de capítulos del overlay de pausa (solo TV): todos los episodios de la serie en scroll
 * horizontal, un paso más abajo desde la fila de íconos, con el actual resaltado y centrado.
 *
 * Sale de [PlayerContent] porque eran seis variables y tres efectos que solo se tocan entre ellos;
 * de la pantalla, lo único que sigue leyendo son las dos guardas del overlay ([revelado] para el
 * timer de auto-ocultado, y cuántos episodios hay para saber si el carrusel existe).
 */
@Stable
internal class EstadoDeCapitulos(
    private val repository: ArkivRepository,
    val listState: LazyListState,
) {
    /** El carrusel está desplegado. Abrirlo/cerrarlo reinicia el timer de auto-ocultado del overlay. */
    var revelado by mutableStateOf(false)
        private set

    var episodios by mutableStateOf<List<Episode>>(emptyList())
        private set

    /**
     * Progreso (posición/duración/visto) de cada episodio, para mostrar "10 de 25 min" en las
     * tarjetas — la barra sola no alcanza para saber cuánto falta en minutos.
     */
    var progresos by mutableStateOf<Map<String, PlaybackEntity>>(emptyMap())
        private set

    /** Stills de TMDB por capítulo (ya cacheados por la pantalla de detalle; acá solo se leen). */
    var stills by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /**
     * Nombres reales de los capítulos, de la MISMA tabla que los stills (`episode_still`) y por el
     * mismo camino que ya usan los dos detalles. El chip mostraba la foto pero no el nombre, así que
     * en el overlay de pausa la serie seguía siendo una fila de "E1 E2 E3" sin decir de qué es cada
     * uno, justo el dato que ese trabajo trajo desde el gateway.
     */
    var titulos by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** El requester vive acá porque el chip que lo recibe lo elige [indiceDe], no la pantalla. */
    val focusRequester = FocusRequester()

    /** Hay carrusel que mostrar: una película o un capítulo suelto no arman fila. */
    val hayCarrusel: Boolean get() = episodios.size > 1

    /**
     * Índice del capítulo actual, o 0 si no se encuentra (p. ej. packs de torrent con id distinto).
     * Se usa tanto para centrar el scroll como para colgar el [focusRequester] en ESE chip: antes el
     * requester solo colgaba del chip `isCurrent`, así que si el actual no estaba en la lista el foco
     * nunca podía entrar al carrusel.
     */
    fun indiceDe(episodioEnCurso: String): Int =
        episodios.indexOfFirst { it.id == episodioEnCurso }.coerceAtLeast(0)

    fun revelar() {
        revelado = true
    }

    fun ocultar() {
        revelado = false
    }

    internal suspend fun cargar(itemId: String) {
        episodios = repository.episodesOf(itemId)
        progresos = repository.playbackForItem(itemId)
        runCatching { repository.ensureEpisodeStills(itemId) }
        repository.observeEpisodeStills(itemId).collect { stills = it }
    }

    internal suspend fun observarTitulos(itemId: String) {
        repository.observeEpisodeTitles(itemId).collect { titulos = it }
    }

    internal suspend fun alRevelar(itemId: String, indiceActual: Int) {
        // Refrescar el progreso al abrir: la posición del episodio actual recién pausado puede no
        // estar reflejada todavía en la carga inicial.
        progresos = repository.playbackForItem(itemId)
        listState.scrollToItem(indiceActual)
        // Mover el foco al chip actual. Un requestFocus() único fallaría en hardware lento
        // (FocusRequester not initialized) y el runCatching lo tragaba en silencio: el foco se
        // quedaba en la fila de botones -> izquierda/derecha hacían seek en vez de navegar. Se
        // reintenta hasta que el requester está enganchado (esperar por condición, no por un delay
        // fijo que no alcanza en hardware lento).
        var landed = false
        repeat(INTENTOS_DE_FOCO) {
            if (landed || !revelado) return@repeat
            landed = runCatching { focusRequester.requestFocus() }.isSuccess
            if (!landed) delay(ESPERA_ENTRE_INTENTOS_MS)
        }
    }
}

@Composable
internal fun rememberEstadoDeCapitulos(repository: ArkivRepository): EstadoDeCapitulos {
    val listState = rememberLazyListState()
    return remember(repository, listState) { EstadoDeCapitulos(repository, listState) }
}

/**
 * Los tres efectos que alimentan el carrusel. Solo corren en TV: en el teléfono el overlay de pausa
 * no lo muestra y pedir los episodios sería gasto puro.
 *
 * Son tres corrutinas y no un `combine` a propósito: la primera se queda colgada para siempre en el
 * `collect` del flow de stills (es lo último que hace), así que los nombres necesitan la suya. La
 * segunda no repite `ensureEpisodeStills` — las dos columnas salen de la misma fila, que ya pidió
 * la primera.
 */
@Composable
internal fun EfectosDeCapitulos(
    estado: EstadoDeCapitulos,
    episodeId: String,
    episodioEnCurso: String,
    isTv: Boolean,
) {
    val itemId = episodeId.substringBefore("::")
    LaunchedEffect(episodeId, isTv) { if (isTv) estado.cargar(itemId) }
    LaunchedEffect(episodeId, isTv) { if (isTv) estado.observarTitulos(itemId) }
    LaunchedEffect(estado.revelado) {
        if (estado.revelado) estado.alRevelar(itemId, estado.indiceDe(episodioEnCurso))
    }
}

/**
 * La fila de capítulos propiamente dicha. [focoDeArriba] es a dónde vuelve el foco con la flecha
 * arriba — el botón de play/pausa de la fila de íconos.
 */
@Composable
internal fun CarruselDeCapitulos(
    estado: EstadoDeCapitulos,
    episodioEnCurso: String,
    focoDeArriba: FocusRequester,
    onElegirEpisodio: (String) -> Unit,
) {
    val indiceActual = estado.indiceDe(episodioEnCurso)
    AnimatedVisibility(visible = estado.revelado, enter = fadeIn(), exit = fadeOut()) {
        LazyRow(
            state = estado.listState,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            itemsIndexed(estado.episodios, key = { _, it -> it.id }) { index, ep ->
                TvEpisodeChip(
                    episode = ep,
                    isCurrent = ep.id == episodioEnCurso,
                    progress = estado.progresos[ep.id],
                    stillUrl = estado.stills[ep.id],
                    episodeTitle = estado.titulos[ep.id],
                    onClick = { onElegirEpisodio(ep.id) },
                    modifier = Modifier
                        // El requester va en el chip del índice actual (no en isCurrent): así el
                        // foco siempre tiene dónde aterrizar aunque el id actual no esté en la
                        // lista (el índice cae en 0).
                        .then(
                            if (index == indiceActual) Modifier.focusRequester(estado.focusRequester) else Modifier,
                        )
                        .focusProperties { up = focoDeArriba }
                        .onKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                            if (e.key == Key.DirectionUp) {
                                estado.ocultar()
                                runCatching { focoDeArriba.requestFocus() }
                                return@onKeyEvent true
                            }
                            // Tragarse las teclas que se saldrían de la fila: abajo del carrusel no
                            // hay nada, así que la búsqueda espacial de Compose enganchaba el
                            // VLCVideoLayout (focusable en TV) — el foco se iba al video y, como
                            // controlsVisible seguía en true, su listener ignoraba todo y ninguna
                            // tecla respondía. Igual en los extremos con izq/der. Se consume acá
                            // (return true) en vez de usar FocusRequester.Cancel porque esa API es
                            // experimental. (El timer de auto-ocultado lo reinicia el
                            // onPreviewKeyEvent del contenedor, que ve estas teclas antes.)
                            e.key == Key.DirectionDown ||
                                (e.key == Key.DirectionLeft && index == 0) ||
                                (e.key == Key.DirectionRight && index == estado.episodios.lastIndex)
                        },
                )
            }
        }
    }
}
