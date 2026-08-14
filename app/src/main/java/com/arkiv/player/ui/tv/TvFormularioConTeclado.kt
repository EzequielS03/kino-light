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
 * Lo que comparten las pantallas de la TV que escriben con el teclado en pantalla y el control
 * remoto: [PanelDeLogin] (`TvPantallaDeEntrada.kt`, entrar/registrarse) y [TvOfertaVincularMagis]
 * (Task 10, ofrecer vincular Magis apenas se entra). Antes esto vivía duplicado adentro de
 * `PanelDeLogin`; se extrajo acá para que un fix de foco, de teclado o del chip de campo no haya que
 * repetirlo en las dos pantallas -si divergen, se arregla un bug en una y no en la otra, mismo
 * motivo por el que `AnonimoSection` es `internal` en `AccountSection.kt`-.
 */

/**
 * Qué campo tiene el foco del teclado en pantalla, y el valor de cada uno. Genérico sobre el enum de
 * campos de cada pantalla ([PanelDeLogin] tiene tres -email/contraseña/licencia-, la oferta de Magis
 * tiene dos -email/contraseña de Magis-) porque el MECANISMO es idéntico en las dos: el teclado
 * escribe sobre "el campo que tiene el foco ahora", sin un click aparte para "entrar" al campo (mismo
 * gesto que `TvSeasonChip` en `TvSearchScreen`).
 */
class TvCamposConFoco<C>(
    inicial: C,
    /**
     * Da forma a lo que se escribe, por campo. Por defecto no toca nada.
     *
     * Existe para el código de licencia: son catorce caracteres con el D-pad, dos de ellos guiones
     * que hay que acordarse de poner, y sobre un alfabeto que a propósito no tiene `I`, `L`, `O`,
     * `0` ni `1`. Formatear al ESCRIBIR (y no solo al enviar, que es lo que se hacía) pone los
     * guiones solo y corrige los ambiguos en el momento, en vez de dejar que el gateway conteste
     * "licencia inválida" a alguien que tipeó exactamente lo que leía. Ver `MascaraDeLicencia`.
     *
     * Va acá y no en cada pantalla porque este es el ÚNICO punto por el que entra texto desde el
     * teclado de la TV: puesto en un solo lugar, ningún campo nuevo se puede olvidar de aplicarlo.
     */
    private val formato: (C, String) -> String = { _, valor -> valor },
) {
    var activo: C by mutableStateOf(inicial)
        private set
    private val valores = mutableStateMapOf<C, String>()

    fun valor(campo: C): String = valores[campo].orEmpty()
    fun valorActivo(): String = valor(activo)
    fun escribir(campo: C, nuevo: String) { valores[campo] = formato(campo, nuevo) }
    fun escribirEnActivo(nuevo: String) = escribir(activo, nuevo)
    fun enfocar(campo: C) { activo = campo }
}

/** `remember` de [TvCamposConFoco] atado a la composición -mismo patrón que cualquier otro estado
 *  recordado de Compose, solo que empaquetado porque son dos piezas (foco + valores) que siempre
 *  viajan juntas. */
@Composable
fun <C> rememberTvCamposConFoco(
    inicial: C,
    formato: (C, String) -> String = { _, valor -> valor },
): TvCamposConFoco<C> = remember { TvCamposConFoco(inicial, formato) }

/**
 * Reparto de ancho entre el teclado y los campos (Task 11). Antes el teclado tenía una columna FIJA
 * de 380.dp y los campos se quedaban con `fillMaxSize()` -TODO el resto-: en un TV de referencia
 * (1920×1080) eso eran campos larguísimos y casi vacíos al lado de un teclado apretado, comprobado
 * en el Fire TV real. Es al revés de lo que conviene: el teclado es lo que se usa TECLA POR TECLA
 * con el control remoto -cada dp de más en una tecla es un blanco más grande y más fácil de acertar
 * a la distancia de un sofá-, mientras que los campos solo MUESTRAN el texto ya tipeado -con que se
 * lean de un vistazo alcanza, no hace falta que crucen la pantalla-.
 *
 * 640.dp de teclado reparte sus 6 columnas ([TvKeyboard] deriva el tamaño de tecla del ancho real,
 * `(maxWidth - gap*5) / 6`) en teclas de exactamente 100.dp -bien por encima del mínimo de 48.dp
 * recomendado para un blanco táctil, y un 76% más grandes que las ~56.7.dp que daba la columna
 * vieja de 380.dp-. 520.dp de campos alcanza y sobra para un email o una contraseña largos sin
 * cortar el texto ([CampoTvChip] usa una sola línea), y deja el resto de la pantalla vacío A
 * PROPÓSITO -mejor un margen sin usar que un campo que no dice nada más por ser más ancho-.
 *
 * `internal`, no `private`: así [TvFormularioConTecladoTest] puede fijar estos números con un test
 * -sin infraestructura de tests de Compose no hay forma de medir el layout real, pero un valor mal
 * puesto acá (p.ej. volver a dejar los campos más anchos que el teclado) sí se puede agarrar como
 * una regresión numérica simple-.
 */
internal const val ANCHO_TECLADO_DP = 640
internal const val ANCHO_CAMPOS_DP = 520

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
                Modifier.fillMaxHeight().width(ANCHO_TECLADO_DP.dp)
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
            // Ancho ACOTADO, no `fillMaxSize()`: ver el KDoc de ANCHO_CAMPOS_DP arriba -este era
            // justo el bug que se arregla en la Task 11, campos cruzando media pantalla vacíos-.
            // El resto del ancho de la fila queda sin usar a propósito.
            Column(
                Modifier.fillMaxHeight().width(ANCHO_CAMPOS_DP.dp).padding(top = 24.dp, end = 48.dp),
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
