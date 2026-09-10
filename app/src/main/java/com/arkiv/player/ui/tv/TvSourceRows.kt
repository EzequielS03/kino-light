package com.arkiv.player.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arkiv.player.ui.catalog.PlaySource
import com.arkiv.player.ui.search.SourceTab
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

/** Alto de las carátulas de Magis y Caracol. */
private val ALTO_TARJETA = 220.dp

/** El margen lateral de las filas del Home ([TvHomeScreen]), para que las dos pantallas alineen. */
private val MARGEN = 48.dp

/**
 * Una fila etiquetada con los resultados de UNA fuente.
 *
 * Magis y Caracol traen imagen propia, así que las dos van con carátula ([TvPosterCard]).
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
                // Las dos fuentes que hay traen carátula, así que las dos van como póster.
                val (titulo, poster) = when (s) {
                    is PlaySource.Magis -> s.result.title to s.result.extra["poster"]
                    is PlaySource.Ditu -> s.result.title to s.result.extra["poster"]
                }
                TvPosterCard(
                    title = titulo,
                    posterUrl = poster,
                    cardHeight = ALTO_TARJETA,
                    modifier = mod,
                ) { onPlay(s) }
            }
        }
    }
}
