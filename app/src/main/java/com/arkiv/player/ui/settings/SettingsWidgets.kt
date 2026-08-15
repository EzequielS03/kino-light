package com.arkiv.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

// Piezas chicas compartidas por los tabs de Ajustes del celular. Vivían dentro de
// SettingsScreen.kt cuando esa pantalla era un solo scroll; al partirla en tabs quedaron
// repartidas entre dos archivos y acá tienen un lugar sin dueño.

@Composable
internal fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = ArkivTextSecondary,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

@Composable
internal fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = ArkivRed,
            selectedLabelColor = Color.White,
        ),
    )
}

@Composable
internal fun Swatch(color: Long, selected: Long, onClick: (Long) -> Unit) {
    Box(
        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(Color(color))
            .border(
                if (color == selected) 3.dp else 1.dp,
                if (color == selected) ArkivRed else Color.Gray,
                RoundedCornerShape(6.dp),
            )
            .clickable { onClick(color) },
    )
}
