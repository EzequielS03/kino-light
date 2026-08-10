package com.arkiv.player.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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

/** Alto de las carátulas de magis. El resto de las fuentes usa mosaicos de este mismo alto. */
private val ALTO_TARJETA = 200.dp

/** Ancho del mosaico de texto. Entra el nombre de un torrent en tres líneas sin quedar ilegible. */
private val ANCHO_MOSAICO = 320.dp

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
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSourceCard(
    source: PlaySource,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    val (tag, tagColor) = etiquetaDe(source)
    val isPack = source is PlaySource.WebPack ||
        (source is PlaySource.Torrent && PackDetector.isPack(source.result.name))

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.width(ANCHO_MOSAICO).height(ALTO_TARJETA)
            .onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ArkivSurfaceHigh,
            focusedContainerColor = ArkivRed,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White)),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.clip(RoundedCornerShape(4.dp)).background(tagColor.copy(alpha = 0.25f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) { Text(tag, color = tagColor, style = MaterialTheme.typography.labelSmall) }
                if (isPack) {
                    Box(
                        Modifier.clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFFFB74D).copy(alpha = 0.25f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) { Text("PACK", color = Color(0xFFFFB74D), style = MaterialTheme.typography.labelSmall) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                tituloDe(source), color = Color.White, style = MaterialTheme.typography.bodyMedium,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            val (meta, metaColor) = metaDe(source)
            Text(meta, color = metaColor, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        }
    }
}

private fun tituloDe(source: PlaySource): String = when (source) {
    is PlaySource.Torrent -> source.result.name
    is PlaySource.Magis -> source.result.title
    is PlaySource.Archive -> source.item.title
    is PlaySource.Web -> source.result.title
    is PlaySource.WebPack -> source.pack.showTitle
}

/** La línea de datos de cada fuente, con el color de su origen. Igual que la fila vertical vieja. */
private fun metaDe(source: PlaySource): Pair<String, Color> = when (source) {
    is PlaySource.Torrent -> {
        val r = source.result
        val q = QualityLabel.extract(r.name)
        buildString {
            append(r.lang.label)
            if (q.isNotBlank()) append("  ·  ").append(q)
            append("  ·  ").append(r.seeders).append(" seeds")
            if (r.sizeLabel.isNotBlank()) append("  ·  ").append(r.sizeLabel)
        } to langColor(r.lang)
    }
    is PlaySource.Magis -> {
        val r = source.result
        val serie = if (r.extra["program_type"] == "teleplay") "Serie" else "Película"
        (serie + if (r.year.isNotBlank()) "  ·  ${r.year}" else "") to Color(0xFF64B5F6)
    }
    is PlaySource.Archive ->
        ("Archive.org" + if (source.item.year.isNotBlank()) "  ·  ${source.item.year}" else "") to Color(0xFF80CBC4)
    is PlaySource.Web -> {
        val r = source.result
        buildString {
            append(r.siteName)
            if (r.language.isNotBlank()) append("  ·  ").append(r.language)
            if (r.quality.isNotBlank()) append("  ·  ").append(r.quality)
        } to Color(0xFFB39DDB)
    }
    is PlaySource.WebPack -> {
        val p = source.pack
        buildString {
            append(p.episodeCount).append(" capítulos")
            if (p.seasons.size > 1) append("  ·  ").append(p.seasons.size).append(" temporadas")
            append("  ·  ").append(p.siteId)
        } to Color(0xFFB39DDB)
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
