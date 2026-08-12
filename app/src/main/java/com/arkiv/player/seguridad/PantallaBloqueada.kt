package com.arkiv.player.seguridad

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * Lo único que se ve cuando el aparato no pasa los controles. No hay botón de continuar a propósito.
 *
 * Se listan los motivos: un aviso que no explica nada es indistinguible de un bug, y quien vea esto
 * en un aparato que cree limpio necesita saber qué se encontró para poder discutirlo.
 */
@Composable
fun PantallaBloqueada(motivos: List<String>) {
    Column(
        modifier = Modifier.fillMaxSize().background(ArkivBlack).padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Este dispositivo no puede ejecutar Arkiv",
            style = MaterialTheme.typography.headlineSmall,
            color = ArkivRed,
            textAlign = TextAlign.Center,
        )
        Text(
            "Se detectó acceso root o una modificación del sistema.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (motivos.isNotEmpty()) {
            Text(
                motivos.joinToString("\n") { "· $it" },
                style = MaterialTheme.typography.bodySmall,
                color = ArkivTextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
    }
}
