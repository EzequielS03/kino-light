package com.arkiv.player.ui.tv

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import com.arkiv.player.data.magis.MagisAccountState
import com.arkiv.player.ui.rememberGraph

/**
 * Los mismos cajones que en el celular ([com.arkiv.player.ui.settings.SettingsScreen]).
 *
 * No hay cajón "Reproducción" acá: sus dos únicos controles (calidad de streaming/descarga y
 * calidad de fuentes web) eran para archive.org y torrent/web, borrados en la poda de esta rama —
 * Magis no usa ninguno de los dos (su CDN decide el bitrate solo). El celular sí conserva un cajón
 * "Reproducción" porque ahí vive además la firma remota del canal en vivo, un control que nunca se
 * portó a esta pantalla.
 */
private enum class TabDeAjustesTv(val etiqueta: String) {
    SUBTITULOS("Subtítulos"),
    CUENTA("Cuenta"),
    APP("App"),
}

/**
 * Ajustes de la TV, repartidos en tabs.
 *
 * Era una columna de 700 líneas que se recorría entera con el D-pad: para llegar a "Buscar
 * actualizaciones" había que bajar por las cuatro calidades, los tres editores de idioma y la lista
 * de aparatos. Los tabs son el gesto que ya usan las raíces del catálogo
 * ([TvSeccionesDeCatalogo]) y comparten la misma pieza ([TvTab]).
 *
 * La fila de tabs vive FUERA de la columna que scrollea. Adentro, el pivote de `bringIntoView` del
 * Fire TV la arrastraría hacia arriba en cuanto el foco bajara al contenido, y volver a ella sería
 * un tanteo.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSettingsScreen() {
    val graph = rememberGraph()
    val cuentaMagis = graph.magisAccount

    // Vincular Magis abre la MISMA pantalla que la oferta al entrar ([TvOfertaVincularMagis]), no
    // un formulario desplegado adentro de la lista de Ajustes. Antes eran dos interfaces distintas
    // para lo mismo: acá campos sueltos con el teclado del sistema -incómodo con el control-, y en
    // la oferta el teclado en pantalla con "Iniciar sesión" y "Crear cuenta". Mantener las dos
    // significaba arreglar cada cosa dos veces, y de hecho las mejoras del flujo de registro
    // (contraseña en el primer paso, "Crear cuenta" habilitado sólo con los campos completos)
    // habían quedado sólo en una.
    var vinculandoMagis by remember { mutableStateOf(false) }
    if (vinculandoMagis) {
        val estadoMagis by cuentaMagis.state.collectAsStateWithLifecycle()
        // Y se cierra sola al vincular. `TvOfertaVincularMagis` no avisa cuando sale bien: no le
        // hacía falta, porque en su uso original (`ArkivTvRoot`, la oferta al entrar) el que la
        // compone reevalúa si todavía hay que ofrecerla y deja de pintarla. Acá el `if` de arriba
        // lo gobierna esta pantalla, así que si nadie mira el estado la vinculación sale bien —el
        // portal la acepta— y la persona se queda mirando el mismo formulario, sin ninguna señal
        // de que pasó algo. Medido en el Fire TV el 2026-08-14: "le di vincular y no dijo nada".
        LaunchedEffect(estadoMagis) {
            if (estadoMagis is MagisAccountState.Linked) vinculandoMagis = false
        }
        TvOfertaVincularMagis(
            cuenta = cuentaMagis,
            // Cerrar es volver a Ajustes, no descartar la oferta para siempre: acá la persona
            // ENTRÓ a vincular a propósito. Por eso no se toca `magisOfertaDescartada`.
            onAhoraNo = { vinculandoMagis = false },
        )
        return
    }

    var tab by rememberSaveable { mutableStateOf(TabDeAjustesTv.SUBTITULOS) }
    // Un scroll por tab: con uno solo compartido, entrar a "Cuenta" desde el fondo de "Subtítulos"
    // dejaba la pantalla arrancada a mitad de camino.
    val scroll = rememberSaveable(tab, saver = ScrollState.Saver) { ScrollState(0) }

    // El foco entra por el primer tab. Sin esto arranca en el primer renglón del contenido y la
    // fila de arriba se descubre de casualidad.
    val focoPrimerTab = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(20) {
            if (runCatching { focoPrimerTab.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 32.dp)) {
        Text("Ajustes", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            // Aire para el zoom y el borde del foco: sin esto el tab enfocado se recorta contra
            // los límites de su propia fila.
            contentPadding = PaddingValues(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(TabDeAjustesTv.entries.size) { i ->
                val t = TabDeAjustesTv.entries[i]
                TvTab(
                    etiqueta = t.etiqueta,
                    seleccionada = t == tab,
                    onClick = { tab = t },
                    modifier = if (i == 0) Modifier.focusRequester(focoPrimerTab) else Modifier,
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (tab) {
                TabDeAjustesTv.SUBTITULOS -> TvSettingsSubtitulos()
                TabDeAjustesTv.CUENTA -> TvSettingsCuenta(cuentaMagis, onVincularMagis = { vinculandoMagis = true })
                TabDeAjustesTv.APP -> TvSettingsApp()
            }
        }
    }
}
