package com.arkiv.player.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arkiv.player.ui.readingWidth
import com.arkiv.player.ui.rememberGraph

/**
 * The Settings drawers. "Reproducción" was dropped from this row (its controls -- quality/source
 * pickers -- were all pruned from this branch; see the deleted `ReproduccionTab.kt`), so today
 * "Subtítulos" opens the screen.
 */
private enum class SettingsTab(val label: String) {
    SUBTITLES("Subtítulos"),
    ACCOUNT("Cuenta"),
    APP("App"),
}

/**
 * The phone's Ajustes, spread across tabs.
 *
 * Used to be a single column with eight blocks chained together: to reach "Mis aparatos" you had
 * to pass through the three language editors and the subtitle color palette. Each tab builds its
 * own state --only the visible one subscribes to the preferences it shows-- and the tab row lives
 * outside the scroll, so it's always within reach.
 */
@Composable
fun SettingsScreen(contentPadding: PaddingValues, onOpenDownloads: () -> Unit = {}) {
    val graph = rememberGraph()
    var tab by rememberSaveable { mutableStateOf(SettingsTab.SUBTITLES) }
    // One scroll per tab: with a single shared one, entering "Cuenta" from the bottom of
    // "Subtítulos" left the screen starting halfway down.
    val scroll = rememberSaveable(tab, saver = ScrollState.Saver) { ScrollState(0) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .readingWidth()
                .fillMaxSize()
                .padding(top = contentPadding.calculateTopPadding()),
        ) {
            Text(
                "Ajustes",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsTab.entries.forEach { t ->
                    Chip(t.label, t == tab) { tab = t }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(horizontal = 20.dp),
            ) {
                when (tab) {
                    SettingsTab.SUBTITLES -> SubtitlesTab()
                    SettingsTab.ACCOUNT -> AccountSection(graph.magisAccount)
                    SettingsTab.APP -> AppTab(onOpenDownloads = onOpenDownloads)
                }
                // The bottom shell adds the air below: the tabs don't need to know there's a
                // navigation bar under them.
                Spacer(Modifier.height(contentPadding.calculateBottomPadding() + 32.dp))
            }
        }
    }
}
