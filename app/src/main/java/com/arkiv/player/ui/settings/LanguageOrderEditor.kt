package com.arkiv.player.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.arkiv.player.playback.LangOrderEdits
import com.arkiv.player.playback.TrackLang
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

/** Idiomas ofrecidos para audio. `DUAL` aplica a pistas multi-audio de los releases. */
val IDIOMAS_AUDIO = listOf(
    TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH,
    TrackLang.DUAL, TrackLang.ENGLISH, TrackLang.JAPANESE,
)

/** Para subtítulos no se ofrece `DUAL`: no existe una pista de texto "dual". */
val IDIOMAS_SUBTITULO = listOf(
    TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH,
    TrackLang.ENGLISH, TrackLang.JAPANESE,
)

fun TrackLang.etiqueta(): String = when (this) {
    TrackLang.LATINO -> "Español latino"
    TrackLang.CASTELLANO -> "Castellano"
    TrackLang.SPANISH -> "Español (genérico)"
    TrackLang.DUAL -> "Dual / multi-audio"
    TrackLang.ENGLISH -> "Inglés"
    TrackLang.JAPANESE -> "Japonés"
    TrackLang.UNKNOWN -> "Desconocido"
}

/**
 * Lista ordenada de idiomas: los elegidos arriba y numerados (el reproductor los recorre en ese
 * orden), los no elegidos abajo en gris. Se edita con check + flechas en vez de arrastrar, porque el
 * mismo patrón tiene que funcionar con el control remoto del TV.
 */
@Composable
fun LanguageOrderEditor(
    title: String,
    options: List<TrackLang>,
    order: List<TrackLang>,
    onChange: (List<TrackLang>) -> Unit,
) {
    Text(
        title,
        style = MaterialTheme.typography.bodyMedium,
        color = ArkivTextSecondary,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
    Column {
        val sinElegir = options.filterNot { it in order }
        order.forEachIndexed { i, lang ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = true,
                    onCheckedChange = { onChange(LangOrderEdits.toggle(order, lang)) },
                    colors = CheckboxDefaults.colors(checkedColor = ArkivRed),
                )
                Text("${i + 1}. ${lang.etiqueta()}", color = Color.White, modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange(LangOrderEdits.moveUp(order, lang)) }, enabled = i > 0) {
                    Text("▲", color = if (i > 0) Color.White else ArkivTextSecondary)
                }
                TextButton(
                    onClick = { onChange(LangOrderEdits.moveDown(order, lang)) },
                    enabled = i < order.lastIndex,
                ) {
                    Text("▼", color = if (i < order.lastIndex) Color.White else ArkivTextSecondary)
                }
            }
        }
        sinElegir.forEach { lang ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = false,
                    onCheckedChange = { onChange(LangOrderEdits.toggle(order, lang)) },
                    colors = CheckboxDefaults.colors(checkedColor = ArkivRed),
                )
                Text(lang.etiqueta(), color = ArkivTextSecondary, modifier = Modifier.weight(1f))
            }
        }
    }
}
