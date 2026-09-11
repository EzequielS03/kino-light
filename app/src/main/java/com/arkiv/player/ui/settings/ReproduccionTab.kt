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
 * Used to hold every control that decided **what quality and source** to play. It was the tab
 * people touched most, so it's still the one that opens the screen.
 *
 * The streaming/download quality and torrent/web pickers were removed with this branch's pruning
 * (controls for sources that no longer exist: archive.org and torrent/web). The "Force server"
 * control for the live-channel signature was also removed afterwards, once the phone started
 * signing segments on its own with no server-side fallback (commit `bf3c3788`). Magis never used
 * any of these settings anyway (its CDN picks the bitrate on its own), so this tab renders nothing
 * today.
 */
@Composable
internal fun ReproduccionTab() {
    val graph = rememberGraph()
    val settings = graph.settings

}
