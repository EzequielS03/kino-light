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

/** Una tecla del teclado en pantalla del TV. */
sealed interface TvKey {
    data class Char(val c: kotlin.Char) : TvKey
    data object Space : TvKey
    data object Backspace : TvKey
}

/** Grilla alfabética estilo Amazon: A-Z y 0-9 en 6 columnas, con espacio/borrar al final. */
val TV_KEYBOARD_ROWS: List<List<TvKey>> = buildList {
    val chars = (('A'..'Z') + ('0'..'9')).map { TvKey.Char(it) }
    chars.chunked(6).forEach { add(it) }
    add(listOf(TvKey.Space, TvKey.Backspace))
}

/** Reductor puro del texto escrito con el control. */
fun applyKey(text: String, key: TvKey): String = when (key) {
    is TvKey.Char -> text + key.c
    TvKey.Space -> "$text "
    TvKey.Backspace -> text.dropLast(1)
}

/** Etiqueta visible de una tecla. */
private fun TvKey.label(): String = when (this) {
    is TvKey.Char -> c.toString()
    TvKey.Space -> "␣"
    TvKey.Backspace -> "⌫"
}

/** Descripción accesible de una tecla. */
private fun TvKey.contentDescription(): String = when (this) {
    is TvKey.Char -> c.toString()
    TvKey.Space -> "Espacio"
    TvKey.Backspace -> "Borrar"
}

@OptIn(ExperimentalTvMaterial3Api::class)
/** Teclado en pantalla navegable con D-pad: grilla de [TV_KEYBOARD_ROWS] que escribe sobre [text]. */
@Composable
fun TvKeyboard(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    firstKeyFocus: FocusRequester? = null,
) {
    val gap = 8.dp
    BoxWithConstraints(modifier) {
        // El tamaño de tecla se deriva del ancho real (6 columnas + 5 separaciones): así entran
        // siempre las 6 columnas, sin cortar la última (F, L, R, X, 3, 9), mida lo que mida.
        val keySize = (maxWidth - gap * 5) / 6
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            TV_KEYBOARD_ROWS.forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEachIndexed { colIndex, key ->
                        val isFirstKey = rowIndex == 0 && colIndex == 0
                        // Espacio y borrar son más anchos (ocupan varias columnas) pero del MISMO
                        // alto que el resto. Con weight + aspectRatio quedaban gigantes: esa fila
                        // tiene solo 2 teclas, se repartían todo el ancho y el alto seguía al ancho.
                        val keyWidth = when (key) {
                            TvKey.Space -> keySize * 4 + gap * 3
                            TvKey.Backspace -> keySize * 2 + gap
                            else -> keySize
                        }
                        // Tecla oscura como el resto de la app; la enfocada se pinta de rojo Arkiv
                        // (se distingue de lejos mucho mejor que un cambio de brillo).
                        Surface(
                            onClick = { onTextChange(applyKey(text, key)) },
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
