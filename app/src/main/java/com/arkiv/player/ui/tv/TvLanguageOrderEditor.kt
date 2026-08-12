package com.arkiv.player.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.playback.LangOrderEdits
import com.arkiv.player.playback.TrackLang
import com.arkiv.player.ui.settings.etiqueta
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * Misma lógica que el editor del celular ([com.arkiv.player.ui.settings.LanguageOrderEditor]) pero
 * con los componentes de `androidx.tv.material3`: el `Surface` de la librería de TV es el que sabe
 * pintarse al recibir foco del control remoto. La lógica de reordenar es compartida
 * ([LangOrderEdits]), así que las dos pantallas no se pueden desincronizar.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvLanguageOrderEditor(
    title: String,
    options: List<TrackLang>,
    order: List<TrackLang>,
    onChange: (List<TrackLang>) -> Unit,
) {
    Text(title, color = ArkivTextSecondary, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        order.forEachIndexed { i, lang ->
            Row(
                Modifier.fillMaxWidth(0.6f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvLangBoton("✓ ${i + 1}. ${lang.etiqueta()}", Modifier.weight(1f)) {
                    onChange(LangOrderEdits.toggle(order, lang))
                }
                TvLangBoton("▲") { onChange(LangOrderEdits.moveUp(order, lang)) }
                TvLangBoton("▼") { onChange(LangOrderEdits.moveDown(order, lang)) }
            }
        }
        options.filterNot { it in order }.forEach { lang ->
            TvLangBoton(lang.etiqueta(), Modifier.fillMaxWidth(0.6f)) {
                onChange(LangOrderEdits.toggle(order, lang))
            }
        }
    }
}

/**
 * Versión TV de [com.arkiv.player.ui.settings.LanguageChecklistEditor]: los mismos idiomas pero SIN
 * flechas, porque acá el orden no significa nada. Cada fila es un botón que prende/apaga el ✓.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvLanguageChecklist(
    title: String,
    subtitle: String,
    options: List<TrackLang>,
    selected: List<TrackLang>,
    onChange: (List<TrackLang>) -> Unit,
) {
    Text(title, color = ArkivTextSecondary, modifier = Modifier.padding(top = 20.dp, bottom = 2.dp))
    Text(subtitle, color = ArkivTextSecondary, modifier = Modifier.padding(bottom = 8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { lang ->
            val marca = if (lang in selected) "✓ " else "   "
            TvLangBoton("$marca${lang.etiqueta()}", Modifier.fillMaxWidth(0.6f)) {
                onChange(LangOrderEdits.toggle(selected, lang))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvLangBoton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        // Mismo estilo compartido que el resto de los botones de Ajustes del TV (TvButtonStyle.kt):
        // sin colores explícitos el Surface de tv.material3 cae en el esquema claro por defecto de
        // la librería y el botón se ve blanco.
        colors = arkivTvSurfaceColors(),
        border = arkivTvSurfaceBorder(),
    ) {
        Text(label, color = Color.White, modifier = Modifier.padding(12.dp))
    }
}
