package com.arkiv.player.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay

/**
 * Teclado en pantalla + control remoto para las pantallas de la TV que escriben texto. Hoy el único
 * llamador es [TvOfertaVincularMagis] (email/contraseña de Magis): originalmente esto vivía
 * duplicado adentro de `PanelDeLogin` (login de Kino, `TvPantallaDeEntrada.kt`) y se extrajo acá para
 * no repetir un fix de foco, de teclado o del chip de campo en las dos pantallas -`PanelDeLogin` y
 * `TvPantallaDeEntrada.kt` se borraron enteras en la Task 9 (sub-proyecto 2B) junto con el resto del
 * login de Kino, así que ese motivo ya no aplica, pero el archivo se quedó como está por si vuelve a
 * hacer falta compartir esto con una segunda pantalla-.
 */

/**
 * Qué campo tiene el foco del teclado en pantalla, y el valor de cada uno. Genérico sobre el enum de
 * campos de la pantalla que lo usa (la oferta de Magis tiene dos -email/contraseña de Magis-) porque
 * el MECANISMO no depende de cuáles sean: el teclado escribe sobre "el campo que tiene el foco
 * ahora", sin un click aparte para "entrar" al campo (mismo gesto que `TvSeasonChip` en
 * `TvSearchScreen`).
 */
class TvCamposConFoco<C>(inicial: C) {
    var activo: C by mutableStateOf(inicial)
        private set
    private val valores = mutableStateMapOf<C, String>()

    fun valor(campo: C): String = valores[campo].orEmpty()
    fun valorActivo(): String = valor(activo)
    fun escribir(campo: C, nuevo: String) { valores[campo] = nuevo }
    fun escribirEnActivo(nuevo: String) = escribir(activo, nuevo)
    fun enfocar(campo: C) { activo = campo }
}

/** `remember` de [TvCamposConFoco] atado a la composición -mismo patrón que cualquier otro estado
 *  recordado de Compose, solo que empaquetado porque son dos piezas (foco + valores) que siempre
 *  viajan juntas. */
@Composable
fun <C> rememberTvCamposConFoco(inicial: C): TvCamposConFoco<C> = remember { TvCamposConFoco(inicial) }

/**
 * Width split between the keyboard and the fields (Task 11), by WEIGHT rather than a fixed dp: a
 * reference TV is 960.dp wide (1920px at density 320, measured on the Fire TV), and the row doesn't
 * wrap, so any fixed pair of widths that overshoots that draws off-screen — the fields column (and
 * the "Crear cuenta" button inside it) got clipped on the right, making the sign-up option look
 * like it didn't exist from the couch.
 *
 * The keyboard gets more weight than the fields on purpose: it's what gets used KEY BY KEY with the
 * remote -every extra dp on a key is a bigger, easier target at sofa distance- while the fields only
 * DISPLAY text already typed -legible at a glance is enough, they don't need to cross the screen-.
 */
internal const val PESO_TECLADO = 1.2f
internal const val PESO_CAMPOS = 1f

/**
 * Layout de dos columnas -teclado fijo a la izquierda, campos a la derecha- con foco inicial en el
 * primer campo (con el mismo reintento que ya usaba `PanelDeLogin`: pedirlo en la primera composición
 * falla en silencio porque el nodo todavía no está colocado, comprobado en el Fire TV). [campos]
 * recibe el [FocusRequester] del primer campo para que quien arma la columna decida a cuál chip se lo
 * pone -normalmente el primero-.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvTecladoYCampos(
    titulo: String,
    subtitulo: String,
    modoTeclado: TvKeyboardMode,
    onModo: (TvKeyboardMode) -> Unit,
    textoActivo: String,
    onTextoActivoChange: (String) -> Unit,
    extras: List<Char> = emptyList(),
    campos: @Composable ColumnScope.(focoPrimerCampo: FocusRequester) -> Unit,
) {
    val focoPrimerCampo = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(20) {
            if (runCatching { focoPrimerCampo.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
            delay(50)
        }
    }

    // Pantalla completa: sin nada encima, el teclado tiene el alto entero y sus teclas de modo no
    // quedan cortadas contra el borde inferior -que en un televisor cae justo en la zona de overscan-.
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 24.dp, start = 48.dp, end = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(titulo, style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(subtitulo, style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
        }
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxHeight().weight(PESO_TECLADO)
                    .padding(start = 48.dp, end = 24.dp, bottom = 16.dp),
            ) {
                TvKeyboard(
                    text = textoActivo,
                    onTextChange = onTextoActivoChange,
                    // Los extras (p.ej. `@`/`.` en el email) los decide quien llama, según qué campo
                    // esté activo: acá solo se pasan tal cual llegan.
                    rows = tvKeyboardRows(modoTeclado, extras = extras),
                    onModo = onModo,
                )
            }
            // Ancho por PESO y no fijo: ver el KDoc de PESO_TECLADO. Con `width(520.dp)` la columna
            // se salía de la pantalla y se llevaba puesto el botón de crear cuenta.
            Column(
                Modifier.fillMaxHeight().weight(PESO_CAMPOS).padding(top = 24.dp, end = 48.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                campos(focoPrimerCampo)
            }
        }
    }
}

/**
 * Selector de campo con el mismo gesto que `TvSeasonChip` (`TvSearchScreen`): mover el foco ahí
 * ([onFocus]) alcanza para que se vuelva el destino del teclado en pantalla, sin un click aparte
 * -pero el click también funciona, para quien llega con un clic directo-. Muestra el valor actual
 * (o un placeholder si está vacío) para que la persona vea qué tiene tipeado sin adivinar.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun CampoTvChip(
    etiqueta: String,
    valor: String,
    activo: Boolean,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    enmascarado: Boolean = false,
) {
    Surface(
        onClick = onFocus,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() }
            .let { if (enmascarado) it.semantics { password() } else it },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (activo) ArkivRed.copy(alpha = 0.28f) else ArkivSurfaceHigh,
            focusedContainerColor = ArkivRed.copy(alpha = 0.55f),
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White)),
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(etiqueta, style = MaterialTheme.typography.labelSmall, color = ArkivTextSecondary)
            Text(
                valor.ifBlank { "—" },
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

/**
 * Botón de mostrar/ocultar contraseña, idéntico en las dos pantallas. El campo que muestra el valor
 * (un [CampoTvChip] con `enmascarado = !visible`) es responsabilidad de quien llama -acá solo va el
 * botón que alterna [visible]-.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvBotonMostrarPassword(visible: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = arkivTvSurfaceColors(),
        border = arkivTvSurfaceBorder(),
    ) {
        Text(
            if (visible) "Ocultar contraseña" else "Mostrar contraseña",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
