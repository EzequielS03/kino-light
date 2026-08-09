package com.arkiv.player.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkiv.player.data.ArchiveSearchResult
import com.arkiv.player.data.catalog.TorrentLang
import com.arkiv.player.data.catalog.TorrentResult
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary

/** Una fuente reproducible: un torrent (va al player de torrent) o un ítem de archive.org (player normal). */
sealed interface PlaySource {
    data class Torrent(val result: TorrentResult) : PlaySource
    data class Archive(val item: ArchiveSearchResult) : PlaySource
    data class Web(val result: com.arkiv.player.data.catalog.web.WebResult) : PlaySource
    data class WebPack(val pack: com.arkiv.player.data.catalog.mirror.MirrorWebPack) : PlaySource

    /** Resultado del portal Magis (solo VOD). El `ref` es opaco: se manda tal cual a
     *  `/v1/resolve` y la app nunca lo interpreta. */
    data class Magis(val result: com.arkiv.player.data.gateway.GatewayResult) : PlaySource
}

/** Color de acento por origen — el mismo en la fila, la sección y los chips de filtro. */
val ArkivWebViolet = Color(0xFFB39DDB)
val ArkivArchiveTeal = Color(0xFF80CBC4)
/** Verde de "mi biblioteca": los capítulos que subimos nosotros, servidos por el mirror. */
val ArkivLibraryGreen = Color(0xFF81C784)
val ArkivPackAmber = Color(0xFFFFB74D)
/** Azul de Magis: el portal IPTV, distinto de web (violeta) y archive (turquesa). */
val ArkivMagisBlue = Color(0xFF64B5F6)

fun accentOf(source: PlaySource): Color = when (source) {
    is PlaySource.Torrent -> ArkivRed
    is PlaySource.Archive -> ArkivArchiveTeal
    is PlaySource.Web, is PlaySource.WebPack -> ArkivWebViolet
    is PlaySource.Magis -> ArkivMagisBlue
}

/** Dato suelto de una fuente (calidad, idioma, seeds, tamaño) como pastilla. Leer una línea corrida
 *  de "Latino · 1080p · 12 seeds · 4.2 GB" cuesta; separados se escanean de un vistazo. */
@Composable
fun MetaChip(text: String, color: Color = ArkivTextSecondary, strong: Boolean = false) {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = if (strong) 0.22f else 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text, color = color, style = MaterialTheme.typography.labelSmall,
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * Sección colapsable por tipo de fuente (TORRENT/WEB/ARCHIVE) con contador y spinner propio.
 * [onDownload], si no es null, agrega un botón de "Guardar en el dispositivo" a cada fila. Lo reciben
 * las tres secciones: el archivo final queda en el celular, no en la NUC.
 */
@Composable
fun SourceSection(
    tag: String,
    tagColor: Color,
    items: List<PlaySource>,
    loading: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean,
    onDownload: ((PlaySource) -> Unit)? = null,
    onPlay: (PlaySource) -> Unit,
) {
    Column(Modifier.padding(top = 4.dp)) {
        SourceSectionHeader(tag, tagColor, items.size, loading, expanded, onToggle)
        if (expanded) {
            items.forEach { s ->
                SourceRow(s, enabled = enabled, onDownload = onDownload?.let { cb -> { cb(s) } }) { onPlay(s) }
            }
            if (items.isEmpty() && !loading) {
                Text(
                    "Sin resultados", color = ArkivTextSecondary, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                )
            }
        }
    }
}

/** Cabecera de una sección, aparte para poder usarla suelta dentro de un LazyColumn. */
@Composable
fun SourceSectionHeader(
    tag: String,
    tagColor: Color,
    count: Int,
    loading: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(width = 3.dp, height = 16.dp).clip(RoundedCornerShape(2.dp)).background(tagColor))
        Text(
            tag, color = Color.White, style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text("$count", color = ArkivTextSecondary, style = MaterialTheme.typography.labelMedium)
        if (loading) CircularProgressIndicator(color = tagColor, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        Spacer(Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "Colapsar" else "Expandir", tint = ArkivTextSecondary,
        )
    }
}

/**
 * Fila de una fuente, como tarjeta: barra de acento del color del origen a la izquierda, nombre en
 * blanco y los datos sueltos (calidad/idioma/seeds/tamaño) en pastillas. El color de la barra dice
 * el origen sin gastar una etiqueta de texto en cada fila.
 */
@Composable
fun SourceRow(source: PlaySource, enabled: Boolean, onDownload: (() -> Unit)? = null, onClick: () -> Unit) {
    val accent = accentOf(source)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ArkivSurfaceHigh.copy(alpha = 0.55f))
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(56.dp).background(accent))
        Icon(
            Icons.Default.PlayArrow, contentDescription = null, tint = accent,
            modifier = Modifier.padding(horizontal = 10.dp).size(20.dp),
        )
        Column(Modifier.weight(1f).padding(vertical = 10.dp, horizontal = 2.dp)) {
            when (source) {
                is PlaySource.Torrent -> {
                    val r = source.result
                    Text(
                        r.name, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Aviso de PACK: sin esto, al buscar un capítulo puedes elegir sin saberlo un
                        // pack de temporada de decenas de GB del que solo verás un episodio.
                        if (com.arkiv.player.data.catalog.PackDetector.isPack(r.name)) {
                            MetaChip("PACK", ArkivPackAmber, strong = true)
                        }
                        MetaChip(r.lang.label, langColor(r.lang))
                        val q = com.arkiv.player.data.catalog.QualityLabel.extract(r.name)
                        if (q.isNotBlank()) MetaChip(q)
                        MetaChip("${r.seeders} seeds")
                        if (r.sizeLabel.isNotBlank()) MetaChip(r.sizeLabel)
                    }
                }
                is PlaySource.Archive -> {
                    val item = source.item
                    Text(
                        item.title, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Nuestras propias subidas no salen del buscador de archive.org (van con
                        // identificador/título hasheados y no matchean por título): las trae el
                        // mirror por tmdb_id. Se marcan distinto porque son las nuestras — de acá
                        // en adelante se reproducen igual que cualquier ítem público.
                        if (item.fromLibrary) {
                            MetaChip("Mi biblioteca", ArkivLibraryGreen, strong = true)
                        } else {
                            MetaChip("Archive.org", ArkivArchiveTeal)
                        }
                        // El # de episodios distingue una serie completa de un fragmento de 1 capítulo
                        // que se llama igual (p. ej. "Get Backers": completa = 49 eps vs "Capítulo # 01" = 1).
                        if (item.episodeCount > 1) MetaChip("${item.episodeCount} episodios")
                        if (item.year.isNotBlank()) MetaChip(item.year)
                    }
                }
                is PlaySource.Web -> {
                    val r = source.result
                    Text(
                        r.title, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        MetaChip(r.siteName, ArkivWebViolet)
                        if (r.language.isNotBlank()) MetaChip(r.language)
                        if (r.quality.isNotBlank()) MetaChip(r.quality)
                    }
                }
                is PlaySource.WebPack -> {
                    val p = source.pack
                    Text(
                        p.showTitle, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        MetaChip("PACK", ArkivPackAmber, strong = true)
                        MetaChip("${p.episodeCount} capítulos")
                        if (p.seasons.size > 1) MetaChip("${p.seasons.size} temporadas")
                        MetaChip(p.siteId, ArkivWebViolet)
                    }
                }
                is PlaySource.Magis -> {
                    val r = source.result
                    Text(
                        r.title, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        MetaChip("Magis", ArkivMagisBlue)
                        if (r.extra["program_type"] == "teleplay") MetaChip("Serie")
                        if (r.year.isNotBlank()) MetaChip(r.year)
                        if (r.lang.isNotBlank()) MetaChip(r.lang)
                    }
                }
            }
        }
        if (onDownload != null) {
            IconButton(onClick = onDownload, enabled = enabled) {
                Icon(Icons.Default.Download, contentDescription = "Descargar offline", tint = accent)
            }
        }
        Spacer(Modifier.width(8.dp))
    }
}

fun langColor(l: TorrentLang): Color = when (l) {
    TorrentLang.LATINO -> Color(0xFF4CAF50)
    TorrentLang.DUAL -> Color(0xFF26A69A)
    TorrentLang.CASTELLANO -> Color(0xFFFFC107)
    TorrentLang.ENGLISH -> Color(0xFF64B5F6)
    TorrentLang.JAP_SUB -> Color(0xFFBA68C8)
    TorrentLang.OTHER -> ArkivTextSecondary
}
