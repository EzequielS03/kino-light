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
 * The extended keyboard's variants. UPPER/LOWER/SYMBOLS are the three from Task 9 (TV login),
 * pickable by hand with the mode row [tvKeyboardRows] builds. Search (the only consumer of
 * [TV_KEYBOARD_ROWS]) doesn't need any of the three -it stays with fixed uppercase-.
 */
enum class TvKeyboardMode { UPPER, LOWER, SYMBOLS }

/** A key on the TV's on-screen keyboard. */
sealed interface TvKey {
    data class Char(val c: kotlin.Char) : TvKey
    data object Space : TvKey
    data object Backspace : TvKey

    /** Switches variant (upper/lower/symbols). Does NOT touch the text -[applyKey] ignores it-,
     *  it's interpreted by whoever builds the grid ([tvKeyboardRows]) and whoever listens to
     *  `onMode` in [TvKeyboard]. */
    data class Mode(val mode: TvKeyboardMode) : TvKey
}

/** Amazon-style alphabetic grid: A-Z and 0-9 in 6 columns, with space/backspace at the end.
 *  Today consumed only by the TV's search -uppercase with no symbols, which is all it needs-, so
 *  it's left intact on purpose (Task 9): changing its shape is unnecessary and risks the only
 *  path that already has tests and a real consumer. The extended keyboard lives separately, in
 *  [tvKeyboardRows]. */
val TV_KEYBOARD_ROWS: List<List<TvKey>> = buildList {
    val chars = (('A'..'Z') + ('0'..'9')).map { TvKey.Char(it) }
    chars.chunked(6).forEach { add(it) }
    add(listOf(TvKey.Space, TvKey.Backspace))
}

/**
 * Extended keyboard's symbols: 18 of the 32 ASCII punctuation signs (without the slash, which
 * already has its own key). 14 were left out on purpose, all for the same reason the license's
 * alphabet already solved (`ALFABETO` in `licencias/codigo.py`, without 0/O/1/I/L): at sofa
 * distance and with a TV grid's small typography, they cost more than they're worth.
 * - `" ' ``` (double quote, single quote, and backtick): nearly indistinguishable from each other
 *   and from the comma.
 * - `,`: visually similar to the apostrophe and adds little that `.` doesn't already cover in a
 *   password.
 * - `< >`: read as direction arrows on a screen navigated with the D-pad.
 * - `[ ] { }`: two pairs of brackets similar to each other, rare in real passwords.
 * - `^ ~`: very rare use in typical passwords, easy to confuse with `-`/`=` at small size.
 * - `| \`: nearly indistinguishable from `/` and from `l`/`1`/`I` -the same ambiguity the
 *   license's alphabet already avoids, applied here to symbols-.
 * What's left covers what the brief asks for (`@` and `.` for email, `-` for the license) plus
 * the most common punctuation in generated passwords: `! # $ % & ( ) * + = ? _ : ;`.
 */
private val TV_SYMBOLS: List<kotlin.Char> = listOf(
    '@', '.', '-', '_', '!', '?', '#', '$', '%', '&', '*', '(', ')', '+', '=', ':', ';', '/',
)

/**
 * Extended keyboard's grid (Task 9): uppercase, lowercase, or symbols depending on [mode], plus a
 * row to switch variant and the usual space/backspace one. Kept separate from
 * [TV_KEYBOARD_ROWS] to avoid risking the contract search already uses (see its comment).
 */
fun tvKeyboardRows(
    mode: TvKeyboardMode,
    /**
     * Keys added to the letter grid, without switching layer.
     *
     * Exists for the email field: `@` and `.` are needed in EVERY direction, and sending the
     * person to the symbols layer and back for each one is four unnecessary remote presses.
     * Same idea as a phone keyboard, which shows the at-sign when the field is an email.
     */
    extras: List<kotlin.Char> = emptyList(),
): List<List<TvKey>> = buildList {
    val base: List<kotlin.Char> = when (mode) {
        TvKeyboardMode.UPPER -> ('A'..'Z') + ('0'..'9')
        TvKeyboardMode.LOWER -> ('a'..'z') + ('0'..'9')
        TvKeyboardMode.SYMBOLS -> TV_SYMBOLS
    }
    // The extras aren't repeated if the layer already has them (the symbols one includes @ and .).
    val chars: List<TvKey> = (base + extras.filterNot { it in base }).map { TvKey.Char(it) }
    chars.chunked(6).forEach { add(it) }
    add(
        listOf(
            TvKey.Mode(TvKeyboardMode.UPPER),
            TvKey.Mode(TvKeyboardMode.LOWER),
            TvKey.Mode(TvKeyboardMode.SYMBOLS),
        ),
    )
    add(listOf(TvKey.Space, TvKey.Backspace))
}

/** Pure reducer of the text typed with the remote. A [TvKey.Mode] key doesn't touch it: it only
 *  changes which grid is shown, and that's handled by whoever builds [tvKeyboardRows], not this
 *  reducer. */
fun applyKey(text: String, key: TvKey): String = when (key) {
    is TvKey.Char -> text + key.c
    TvKey.Space -> "$text "
    TvKey.Backspace -> text.dropLast(1)
    is TvKey.Mode -> text
}

/** A key's visible label. */
private fun TvKey.label(): String = when (this) {
    is TvKey.Char -> c.toString()
    TvKey.Space -> "␣"
    TvKey.Backspace -> "⌫"
    is TvKey.Mode -> when (mode) {
        TvKeyboardMode.UPPER -> "ABC"
        TvKeyboardMode.LOWER -> "abc"
        TvKeyboardMode.SYMBOLS -> "#+="
    }
}

/** A key's accessible description. */
private fun TvKey.contentDescription(): String = when (this) {
    is TvKey.Char -> c.toString()
    TvKey.Space -> "Espacio"
    TvKey.Backspace -> "Borrar"
    is TvKey.Mode -> when (mode) {
        TvKeyboardMode.UPPER -> "Mayúsculas"
        TvKeyboardMode.LOWER -> "Minúsculas"
        TvKeyboardMode.SYMBOLS -> "Símbolos"
    }
}

/** How many columns of the 6-wide grid a key occupies. Replaces the switch that used to live
 *  inline in the composable: a new key declares its width here, without touching the layout
 *  shared with search. [TvKey.Space]/[TvKey.Backspace]'s widths are the usual ones (4+2=6, no
 *  behavior change); [TvKey.Mode] uses the same width as [TvKey.Backspace] because the mode row
 *  is three equal keys (2+2+2=6). */
private fun TvKey.columnSpan(): Int = when (this) {
    is TvKey.Char -> 1
    TvKey.Space -> 4
    TvKey.Backspace -> 2
    is TvKey.Mode -> 2
}

@OptIn(ExperimentalTvMaterial3Api::class)
/**
 * On-screen keyboard navigable with the D-pad: a grid of [rows] that writes onto [text].
 *
 * [rows] defaults to [TV_KEYBOARD_ROWS] on purpose -so search, which calls `TvKeyboard(...)`
 * without naming `rows`, keeps seeing exactly the same grid as always (Task 9)-. The login form
 * passes [tvKeyboardRows] with the matching variant and listens to [onMode] to switch it.
 */
@Composable
fun TvKeyboard(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    firstKeyFocus: FocusRequester? = null,
    rows: List<List<TvKey>> = TV_KEYBOARD_ROWS,
    /** Fired with a [TvKey.Mode] key: switching variant is the caller's decision (it's the one
     *  holding the state of which variant is active), not this composable's. `null` -the
     *  default, and what search uses- because [TV_KEYBOARD_ROWS] never carries mode keys. */
    onMode: ((TvKeyboardMode) -> Unit)? = null,
) {
    val gap = 8.dp
    BoxWithConstraints(modifier) {
        // Key size is derived from the real width (6 columns + 5 gaps): that way all 6 columns
        // always fit, without clipping the last one (F, L, R, X, 3, 9), whatever the width is.
        //
        // ...but width alone isn't enough: with a generous column, that math gave keys so tall
        // that the last rows -the mode one, space, and backspace- ended up OFF SCREEN. Seen on a
        // Google TV Stick at 1920x1080 at 320dpi, i.e. 960x540 dp: six 106 dp columns needed
        // 850 dp of height and there were only 540.
        //
        // That's why the SMALLER of the two is used: the one width gives and the one the
        // available height gives split across the rows to draw. That way the keyboard always
        // fits entirely, and on a wide, short screen it simply doesn't use all the width it could.
        val byWidth = (maxWidth - gap * 5) / 6
        val byHeight = (maxHeight - gap * (rows.size - 1)) / rows.size
        val keySize = minOf(byWidth, byHeight)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            rows.forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEachIndexed { colIndex, key ->
                        val isFirstKey = rowIndex == 0 && colIndex == 0
                        // Space and backspace are wider (span several columns) but the SAME
                        // height as the rest. With weight + aspectRatio they came out huge: that
                        // row only has 2 keys, they split the whole width and height followed
                        // width.
                        val span = key.columnSpan()
                        val keyWidth = keySize * span + gap * (span - 1)
                        // Key is dark like the rest of the app; the focused one paints Arkiv red
                        // (stands out from a distance much better than a brightness change).
                        Surface(
                            onClick = {
                                // Mode doesn't touch the text (see applyKey): it only signals a
                                // grid switch. If nobody listens to onMode (search, via the
                                // default) this key never shows up -TV_KEYBOARD_ROWS doesn't
                                // include it-, so the branch below changes nothing for that
                                // consumer.
                                if (key is TvKey.Mode) onMode?.invoke(key.mode) else onTextChange(applyKey(text, key))
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
