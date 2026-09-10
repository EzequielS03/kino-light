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
import com.arkiv.player.ui.anchoDeLectura
import com.arkiv.player.ui.rememberGraph

/**
 * Los cajones de Ajustes. El orden es por frecuencia de uso: lo que se toca seguido
 * (calidad) primero, lo que se toca una vez (cuenta, actualizaciones) al final.
 */
private enum class TabDeAjustes(val etiqueta: String) {
    REPRODUCCION("Reproducción"),
    SUBTITULOS("Subtítulos"),
    CUENTA("Cuenta"),
    APP("App"),
}

/**
 * Ajustes del celular, repartidos en tabs.
 *
 * Antes era una sola columna con ocho bloques encadenados: para llegar a "Mis aparatos" había que
 * pasar por los tres editores de idioma y la paleta de colores de los subtítulos. Cada tab arma su
 * propio estado —solo el visible se suscribe a las preferencias que muestra— y la fila de tabs vive
 * fuera del scroll, así queda siempre a mano.
 */
@Composable
fun SettingsScreen(contentPadding: PaddingValues, onOpenDownloads: () -> Unit = {}) {
    val graph = rememberGraph()
    var tab by rememberSaveable { mutableStateOf(TabDeAjustes.REPRODUCCION) }
    // Un scroll por tab: con uno solo compartido, entrar a "Cuenta" desde el fondo de "Subtítulos"
    // dejaba la pantalla arrancada a mitad de camino.
    val scroll = rememberSaveable(tab, saver = ScrollState.Saver) { ScrollState(0) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .anchoDeLectura()
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
                TabDeAjustes.entries.forEach { t ->
                    Chip(t.etiqueta, t == tab) { tab = t }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(horizontal = 20.dp),
            ) {
                when (tab) {
                    TabDeAjustes.REPRODUCCION -> ReproduccionTab()
                    TabDeAjustes.SUBTITULOS -> SubtitulosTab()
                    TabDeAjustes.CUENTA -> AccountSection(graph.cuentaDeMagis)
                    TabDeAjustes.APP -> AppTab(onOpenDownloads = onOpenDownloads)
                }
                // El aire de abajo lo pone la cáscara: los tabs no tienen por qué saber que debajo hay
                // una barra de navegación.
                Spacer(Modifier.height(contentPadding.calculateBottomPadding() + 32.dp))
            }
        }
    }
}
