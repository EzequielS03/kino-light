package com.arkiv.player.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.data.catalog.PackDetector
import com.arkiv.player.data.catalog.QualityLabel
import com.arkiv.player.ui.catalog.PlaySource
import com.arkiv.player.ui.catalog.langColor
import com.arkiv.player.ui.search.SourceTab
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextPrimary
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * Los resultados del buscador del TV, como una fila horizontal por fuente.
 *
 * Antes era una sola lista vertical: con 536 torrents y 20 de magis en la misma columna, magis
 * quedaba sepultado y no había forma de llegar con el control remoto. Con una fila por fuente se
 * ven todas de un vistazo, y el D-pad hace lo natural — derecha recorre una fuente, abajo salta a
 * la siguiente. Es además el lenguaje que el Home del TV ya usa.
 */

/** Alto de las carátulas de magis. */
private val ALTO_TARJETA = 220.dp

/**
 * El mosaico de texto va más grande que la carátula, y a propósito.
 *
 * En magis la imagen hace el trabajo; en torrent/web/archive lo único que hay para decidir es el
 * texto, y esto se lee a dos metros de distancia. Que las filas no midan exactamente igual no
 * molesta: cada fila es de una sola fuente.
 */
private val ALTO_MOSAICO = 230.dp
private val ANCHO_MOSAICO = 380.dp

/** El margen lateral de las filas del Home ([TvHomeScreen]), para que las dos pantallas alineen. */
private val MARGEN = 48.dp

private fun etiquetaDe(source: PlaySource): Pair<String, Color> = when (source) {
    is PlaySource.Torrent -> "TORRENT" to ArkivRed
    is PlaySource.Archive -> "ARCHIVE" to Color(0xFF80CBC4)
    is PlaySource.Web -> "WEB" to Color(0xFFB39DDB)
    is PlaySource.WebPack -> "WEB" to Color(0xFFB39DDB)
    is PlaySource.Magis -> "MAGIS" to Color(0xFF64B5F6)
}

/**
 * Una fuente sin imagen, como mosaico de texto.
 *
 * Torrent, web y archive no traen carátula, y su información ES el texto (seeds, tamaño, calidad,
 * idioma): meterlas en una tarjeta de póster vacía sería perder justo lo que hace falta para
 * elegir. Mismo contenido que tenía la fila vertical anterior, en un ancho fijo.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TvSourceCard(
    source: PlaySource,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    val tagColor = etiquetaDe(source).second
    val isPack = source is PlaySource.WebPack ||
        (source is PlaySource.Torrent && PackDetector.isPack(source.result.name))

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.width(ANCHO_MOSAICO).height(ALTO_MOSAICO)
            .onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        // El foco NO inunda la tarjeta de rojo: borde blanco y un empujón de escala, igual que las
        // tarjetas del Home. Con el fondo rojo, el texto blanco —que es justo lo que hay que leer
        // en estas fuentes— quedaba peleando contra el color.
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ArkivSurfaceHigh,
            focusedContainerColor = ArkivSurfaceHigh,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White)),
        ),
    ) {
        Row(
            Modifier.fillMaxSize()
                // Un tinte del color del origen que se apaga hacia abajo: le da profundidad al
                // rectángulo plano y hace que cada fila se lea como una familia, sin gritar.
                .background(
                    Brush.verticalGradient(
                        listOf(tagColor.copy(alpha = 0.16f), Color.Transparent),
                    ),
                ),
        ) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(tagColor))
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
                // El badge de la fuente NO va: la etiqueta de la fila ya dice "Torrent", y la barra
                // de color lo repite. Sacarlo le devuelve una línea entera al título, que es lo
                // único que de verdad distingue un resultado de otro.
                if (isPack) {
                    TvMetaChip("PACK", Color(0xFFFFB74D), fuerte = true)
                    Spacer(Modifier.height(8.dp))
                }
                // El título toma el espacio libre en vez de dejar media tarjeta vacía.
                Text(
                    tituloDe(source),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Los datos, como pastillas y no como una línea corrida: a dos metros se escanean
                // de un vistazo en vez de leerse palabra por palabra.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    datosDe(source).forEach { (texto, color) -> TvMetaChip(texto, color) }
                }
            }
        }
    }
}

/** Pastilla de dato con estilo de TV (tipografía más grande que la del teléfono). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvMetaChip(texto: String, color: Color, fuerte: Boolean = false) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = if (fuerte) 0.28f else 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(texto, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

private fun tituloDe(source: PlaySource): String = when (source) {
    is PlaySource.Torrent -> source.result.name
    is PlaySource.Magis -> source.result.title
    is PlaySource.Archive -> source.item.title
    is PlaySource.Web -> source.result.title
    is PlaySource.WebPack -> source.pack.showTitle
}

/**
 * Los datos de cada fuente, ya partidos en pastillas y con el color de su origen.
 *
 * Antes era una sola línea con separadores "·". Partirla deja que cada dato se lea solo, que es
 * lo que hace falta cuando la pantalla está a dos metros y lo que decide es "cuántos seeds tiene".
 */
private fun datosDe(source: PlaySource): List<Pair<String, Color>> = when (source) {
    is PlaySource.Torrent -> {
        val r = source.result
        buildList {
            add(r.lang.label to langColor(r.lang))
            QualityLabel.extract(r.name).takeIf { it.isNotBlank() }?.let { add(it to ArkivTextSecondary) }
            add("${r.seeders} seeds" to if (r.seeders > 0) Color(0xFF81C784) else ArkivTextSecondary)
            r.sizeLabel.takeIf { it.isNotBlank() }?.let { add(it to ArkivTextSecondary) }
        }
    }
    is PlaySource.Magis -> {
        val r = source.result
        buildList {
            add((if (r.extra["program_type"] == "teleplay") "Serie" else "Película") to Color(0xFF64B5F6))
            r.year.takeIf { it.isNotBlank() }?.let { add(it to ArkivTextSecondary) }
        }
    }
    is PlaySource.Archive -> buildList {
        add("Archive.org" to Color(0xFF80CBC4))
        source.item.year.takeIf { it.isNotBlank() }?.let { add(it to ArkivTextSecondary) }
    }
    is PlaySource.Web -> {
        val r = source.result
        buildList {
            add(r.siteName to Color(0xFFB39DDB))
            r.language.takeIf { it.isNotBlank() }?.let { add(it to ArkivTextSecondary) }
            r.quality.takeIf { it.isNotBlank() }?.let { add(it to ArkivTextSecondary) }
        }
    }
    is PlaySource.WebPack -> {
        val p = source.pack
        buildList {
            add(p.siteId to Color(0xFFB39DDB))
            add("${p.episodeCount} capítulos" to ArkivTextSecondary)
            if (p.seasons.size > 1) add("${p.seasons.size} temporadas" to ArkivTextSecondary)
        }
    }
}

/**
 * Una fila etiquetada con los resultados de UNA fuente.
 *
 * Magis va con carátula porque es la única que trae imagen propia; el resto va con mosaico de
 * texto. Que dos filas tengan tarjetas distintas es lo normal en una tele — el Home ya mezcla
 * apaisadas con pósters.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
fun LazyListScope.tvFilaDeFuente(
    fuente: SourceTab,
    items: List<PlaySource>,
    enabled: Boolean,
    loading: Boolean,
    /** Solo la PRIMERA fila lo recibe: el foco inicial va a su primera tarjeta, no a la de cada fila. */
    primeraTarjeta: FocusRequester?,
    onPlay: (PlaySource) -> Unit,
) {
    item(key = "fila-${fuente.name}") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(start = MARGEN, top = 20.dp, bottom = 8.dp),
        ) {
            Text(fuente.label, style = MaterialTheme.typography.titleSmall, color = ArkivTextPrimary)
            Text("${items.size}", style = MaterialTheme.typography.labelSmall, color = ArkivTextSecondary)
            if (loading) {
                Text("buscando…", style = MaterialTheme.typography.labelSmall, color = ArkivTextSecondary)
            }
        }
    }
    item(key = "row-${fuente.name}") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = MARGEN),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items, key = { sourceKey(it) }) { s ->
                // El foco arranca en la primera tarjeta de la primera fila. `===` y no `==`: dos
                // resultados pueden ser iguales por valor y no queremos pedir foco dos veces.
                val mod = if (primeraTarjeta != null && s === items.first()) {
                    Modifier.focusRequester(primeraTarjeta)
                } else {
                    Modifier
                }
                if (s is PlaySource.Magis) {
                    TvPosterCard(
                        title = s.result.title,
                        posterUrl = s.result.extra["poster"],
                        cardHeight = ALTO_TARJETA,
                        modifier = mod,
                    ) { onPlay(s) }
                } else {
                    TvSourceCard(s, enabled = enabled, modifier = mod) { onPlay(s) }
                }
            }
        }
    }
}
