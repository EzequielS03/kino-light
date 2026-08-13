package com.arkiv.player.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh

/**
 * Las variantes del teclado extendido. MAYUS/MINUS/SIMBOLOS son las tres de Task 9 (login de la
 * TV), elegibles a mano con la fila de modos que arma [tvKeyboardRows]. La búsqueda (único
 * consumidor de [TV_KEYBOARD_ROWS]) no necesita ninguna de las cuatro -sigue con mayúsculas fijas-.
 *
 * NUMERICO es de la Task 11 (código de verificación al crear una cuenta de Magis desde la TV):
 * SOLO dígitos, sin fila de modos. Magis lo manda numérico -confirmado leyendo el gateway
 * (`registro_confirmar`/`validate_verify_code` en `arkiv-api`, y el sentinela de test "000000" en
 * `test_magis_session.py`), y el celu ya le pide `KeyboardType.Number` a este mismo campo
 * (`AccountSection`/`TvSettingsScreen`)-, así que no hay ninguna letra que ese código pueda tener:
 * ofrecerle a la persona la fila de MAYUS/MINUS/SIMBOLOS sería una opción que nunca sirve para
 * nada, y buscar el dígito correcto entre 26 letras con un D-pad es un paso al pedo que se evita
 * del todo mostrando solo los 10 dígitos. Se llega a esta capa SOLO por código -quien arma la
 * pantalla la fuerza mientras el campo activo es el del código, ver `TvOfertaVincularMagis`-, no
 * por la fila de modos (por eso [tvKeyboardRows] no la agrega ahí).
 */
enum class TvKeyboardMode { MAYUS, MINUS, SIMBOLOS, NUMERICO }

/** Una tecla del teclado en pantalla del TV. */
sealed interface TvKey {
    data class Char(val c: kotlin.Char) : TvKey
    data object Space : TvKey
    data object Backspace : TvKey

    /** Cambia de variante (mayús/minús/símbolos). NO toca el texto -[applyKey] la ignora-, la
     *  interpreta quien arma la grilla ([tvKeyboardRows]) y quien escucha `onModo` en [TvKeyboard]. */
    data class Modo(val modo: TvKeyboardMode) : TvKey
}

/** Grilla alfabética estilo Amazon: A-Z y 0-9 en 6 columnas, con espacio/borrar al final.
 *  Consumida hoy solo por la búsqueda del TV -mayúsculas sin símbolos, que es lo que necesita-, así
 *  que se deja intacta a propósito (Task 9): cambiar su forma es innecesario y arriesga el único
 *  camino que ya tiene tests y un consumidor real. El teclado extendido vive aparte, en
 *  [tvKeyboardRows]. */
val TV_KEYBOARD_ROWS: List<List<TvKey>> = buildList {
    val chars = (('A'..'Z') + ('0'..'9')).map { TvKey.Char(it) }
    chars.chunked(6).forEach { add(it) }
    add(listOf(TvKey.Space, TvKey.Backspace))
}

/**
 * Símbolos del teclado extendido: 18 de los 32 signos de puntuación ASCII (sin la barra, que ya
 * tiene su propia tecla). Se dejaron afuera 14 a propósito, todos por el mismo motivo que el
 * alfabeto de la licencia ya resolvió (`ALFABETO` en `licencias/codigo.py`, sin 0/O/1/I/L): a la
 * distancia de un sofá y con la tipografía chica de una grilla de TV, cuestan más de lo que aportan.
 * - `" ' ``` (comilla doble, simple y acento grave): casi indistinguibles entre sí y de la coma.
 * - `,` : visualmente parecida al apóstrofo y aporta poco que `.` no cubra ya en una contraseña.
 * - `< >` : se leen como flechas de dirección en una pantalla que se navega con D-pad.
 * - `[ ] { }` : dos pares de corchetes parecidos entre sí, raros en contraseñas reales.
 * - `^ ~` : uso rarísimo en contraseñas típicas, fáciles de confundir con `-`/`=` en chico.
 * - `| \` : casi indistinguibles de `/` y de `l`/`1`/`I` -la misma ambigüedad que el alfabeto de
 *   licencia ya evita, acá aplicada a símbolos-.
 * Lo que queda cubre lo que pide el brief (`@` y `.` del email, `-` de la licencia) más la
 * puntuación más común en contraseñas generadas: `! # $ % & ( ) * + = ? _ : ;`.
 */
private val SIMBOLOS_TV: List<kotlin.Char> = listOf(
    '@', '.', '-', '_', '!', '?', '#', '$', '%', '&', '*', '(', ')', '+', '=', ':', ';', '/',
)

/**
 * Grilla del teclado extendido (Task 9): mayúsculas, minúsculas o símbolos según [modo], más una
 * fila para cambiar de variante y la de espacio/borrar de siempre. Separada de [TV_KEYBOARD_ROWS]
 * para no arriesgar el contrato que ya usa la búsqueda (ver su comentario).
 */
fun tvKeyboardRows(
    modo: TvKeyboardMode,
    /**
     * Teclas que se suman a la grilla de letras, sin cambiar de capa.
     *
     * Existe para el campo de email: `@` y `.` estan en TODAS las direcciones, y mandar a la
     * persona a la capa de simbolos y de vuelta por cada una son cuatro pulsaciones de control
     * remoto que no hacen falta. Es la misma idea que un teclado de telefono, que muestra la
     * arroba cuando el campo es un email. Sin efecto en NUMERICO (ver más abajo): un código de
     * verificación no necesita `@`/`.`, y esa capa ya no admite nada que no sea un dígito.
     */
    extras: List<kotlin.Char> = emptyList(),
): List<List<TvKey>> = buildList {
    // NUMERICO es autocontenida (ver el KDoc de TvKeyboardMode): sin la fila de MAYUS/MINUS/
    // SIMBOLOS -no hay letra que un código de verificación pueda tener- y sin espacio -tampoco
    // lleva uno-, así que se arma aparte en vez de compartir la cola común de las otras tres
    // variantes.
    if (modo == TvKeyboardMode.NUMERICO) {
        ('0'..'9').map { TvKey.Char(it) }.chunked(6).forEach { add(it) }
        add(listOf(TvKey.Backspace))
        return@buildList
    }
    val base: List<kotlin.Char> = when (modo) {
        TvKeyboardMode.MAYUS -> ('A'..'Z') + ('0'..'9')
        TvKeyboardMode.MINUS -> ('a'..'z') + ('0'..'9')
        TvKeyboardMode.SIMBOLOS -> SIMBOLOS_TV
        // Inalcanzable -la rama de arriba ya devolvió-; existe solo para que este `when` sea
        // exhaustivo sin un `else` que silenciaría por accidente una variante nueva el día de
        // mañana.
        TvKeyboardMode.NUMERICO -> emptyList()
    }
    // Los extras no se repiten si la capa ya los trae (la de simbolos incluye @ y .).
    val chars: List<TvKey> = (base + extras.filterNot { it in base }).map { TvKey.Char(it) }
    chars.chunked(6).forEach { add(it) }
    add(
        listOf(
            TvKey.Modo(TvKeyboardMode.MAYUS),
            TvKey.Modo(TvKeyboardMode.MINUS),
            TvKey.Modo(TvKeyboardMode.SIMBOLOS),
        ),
    )
    add(listOf(TvKey.Space, TvKey.Backspace))
}

/** Reductor puro del texto escrito con el control. Una tecla de [TvKey.Modo] no lo toca: solo
 *  cambia qué grilla se ve, y eso lo maneja quien arma [tvKeyboardRows], no este reductor. */
fun applyKey(text: String, key: TvKey): String = when (key) {
    is TvKey.Char -> text + key.c
    TvKey.Space -> "$text "
    TvKey.Backspace -> text.dropLast(1)
    is TvKey.Modo -> text
}

/** Etiqueta visible de una tecla. */
private fun TvKey.label(): String = when (this) {
    is TvKey.Char -> c.toString()
    TvKey.Space -> "␣"
    TvKey.Backspace -> "⌫"
    is TvKey.Modo -> when (modo) {
        TvKeyboardMode.MAYUS -> "ABC"
        TvKeyboardMode.MINUS -> "abc"
        TvKeyboardMode.SIMBOLOS -> "#+="
        // Inalcanzable hoy -ninguna fila de modos incluye TvKey.Modo(NUMERICO), ver el KDoc de
        // TvKeyboardMode-; la rama existe para el exhaustive when.
        TvKeyboardMode.NUMERICO -> "123"
    }
}

/** Descripción accesible de una tecla. */
private fun TvKey.contentDescription(): String = when (this) {
    is TvKey.Char -> c.toString()
    TvKey.Space -> "Espacio"
    TvKey.Backspace -> "Borrar"
    is TvKey.Modo -> when (modo) {
        TvKeyboardMode.MAYUS -> "Mayúsculas"
        TvKeyboardMode.MINUS -> "Minúsculas"
        TvKeyboardMode.SIMBOLOS -> "Símbolos"
        TvKeyboardMode.NUMERICO -> "Números" // inalcanzable hoy, ver comentario de label() arriba
    }
}

/** Cuántas columnas de la grilla de 6 ocupa una tecla. Reemplaza el switch que antes vivía inline
 *  en el composable: una tecla nueva declara su ancho acá, sin tocar el layout compartido con la
 *  búsqueda. Los anchos de [TvKey.Space]/[TvKey.Backspace] son los de siempre (4+2=6, sin cambios
 *  de comportamiento); [TvKey.Modo] usa el mismo ancho que [TvKey.Backspace] porque la fila de
 *  modos son tres teclas iguales (2+2+2=6). */
private fun TvKey.columnSpan(): Int = when (this) {
    is TvKey.Char -> 1
    TvKey.Space -> 4
    TvKey.Backspace -> 2
    is TvKey.Modo -> 2
}

@OptIn(ExperimentalTvMaterial3Api::class)
/**
 * Teclado en pantalla navegable con D-pad: grilla de [rows] que escribe sobre [text].
 *
 * [rows] default a [TV_KEYBOARD_ROWS] a propósito -así la búsqueda, que llama a `TvKeyboard(...)`
 * sin nombrar `rows`, sigue viendo exactamente la misma grilla de siempre (Task 9)-. El formulario
 * de login pasa [tvKeyboardRows] con la variante que corresponda y escucha [onModo] para cambiarla.
 */
@Composable
fun TvKeyboard(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    firstKeyFocus: FocusRequester? = null,
    rows: List<List<TvKey>> = TV_KEYBOARD_ROWS,
    /** Se dispara con una tecla [TvKey.Modo]: cambiar de variante es decisión de quien llama (es
     *  quien tiene el estado de qué variante está activa), no de este composable. `null` -el
     *  default, y lo que usa la búsqueda- porque [TV_KEYBOARD_ROWS] nunca trae teclas de modo. */
    onModo: ((TvKeyboardMode) -> Unit)? = null,
) {
    val gap = 8.dp
    BoxWithConstraints(modifier) {
        // El tamaño de tecla se deriva del ancho real (6 columnas + 5 separaciones): así entran
        // siempre las 6 columnas, sin cortar la última (F, L, R, X, 3, 9), mida lo que mida.
        val keySize = (maxWidth - gap * 5) / 6
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            rows.forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEachIndexed { colIndex, key ->
                        val isFirstKey = rowIndex == 0 && colIndex == 0
                        // Espacio y borrar son más anchos (ocupan varias columnas) pero del MISMO
                        // alto que el resto. Con weight + aspectRatio quedaban gigantes: esa fila
                        // tiene solo 2 teclas, se repartían todo el ancho y el alto seguía al ancho.
                        val span = key.columnSpan()
                        val keyWidth = keySize * span + gap * (span - 1)
                        // Tecla oscura como el resto de la app; la enfocada se pinta de rojo Arkiv
                        // (se distingue de lejos mucho mejor que un cambio de brillo).
                        Surface(
                            onClick = {
                                // Modo no toca el texto (ver applyKey): solo avisa que cambie de
                                // grilla. Si nadie escucha onModo (la búsqueda, vía el default) esta
                                // tecla nunca aparece -TV_KEYBOARD_ROWS no la incluye-, así que la
                                // rama de abajo no cambia nada para ese consumidor.
                                if (key is TvKey.Modo) onModo?.invoke(key.modo) else onTextChange(applyKey(text, key))
                            },
                            modifier = Modifier
                                .width(keyWidth)
                                .height(keySize)
                                .semantics { contentDescription = key.contentDescription() }
                                .let { m ->
                                    if (isFirstKey && firstKeyFocus != null) {
                                        m.focusRequester(firstKeyFocus)
                                    } else {
                                        m
                                    }
                                },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = ArkivSurfaceHigh,
                                contentColor = Color.White,
                                focusedContainerColor = ArkivRed,
                                focusedContentColor = Color.White,
                            ),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = key.label(),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
