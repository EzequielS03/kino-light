package com.arkiv.player.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * Todo lo que decide **con qué calidad y de qué fuente** se reproduce. Es el tab que más se toca,
 * así que es el que abre la pantalla.
 *
 * Las calidades de streaming/descarga/torrent/web y el tamaño máximo de torrent se borraron con la
 * poda de esta rama (eran controles para fuentes que ya no existen: archive.org y torrent/web).
 * Magis no usa ninguno de esos ajustes (su CDN decide el bitrate solo). Lo único que sigue vivo acá
 * es la firma remota del canal en vivo.
 */
@Composable
internal fun ReproduccionTab() {
    val graph = rememberGraph()
    val settings = graph.settings

}
