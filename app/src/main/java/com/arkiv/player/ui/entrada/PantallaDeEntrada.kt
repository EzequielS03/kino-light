package com.arkiv.player.ui.entrada

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkiv.player.ui.esTabletHorizontal
import com.arkiv.player.ui.settings.AnonimoSection
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * Se compone en vez de `ArkivRoot`/`ArkivTvRoot` cuando [EntradaViewModel] dice que no hay sesión
 * -`MainActivity` no arma Room, el sync ni las filas del home sin ella-. El formulario de login y
 * registro es [AnonimoSection] (ui/settings/AccountSection.kt) reusado tal cual, no reimplementado.
 */
@Composable
fun PantallaDeEntrada(vm: EntradaViewModel) {
    val aviso by vm.aviso.collectAsStateWithLifecycle()

    Scaffold(containerColor = ArkivBlack) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = if (esTabletHorizontal()) 720.dp else Dp.Unspecified)
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Spacer(Modifier.height(48.dp))
                Text(
                    "KINO",
                    style = MaterialTheme.typography.headlineMedium,
                    color = ArkivRed,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    "Inicia sesión para continuar",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArkivTextSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
                )

                // "No se pudo conectar" (backend_no_disponible / sin red): NO es un error del
                // formulario -el token guardado, si lo hubiera, sigue siendo válido, ver
                // EntradaViewModel.manejarErrorDeCuenta-, así que se muestra aparte, con la opción de
                // descartarlo y reintentar (reintentar acá es simplemente volver a tocar "Entrar").
                aviso?.let { mensaje ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ArkivSurface),
                        modifier = Modifier.padding(bottom = 16.dp),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("No se pudo conectar", style = MaterialTheme.typography.titleSmall, color = ArkivRed)
                            Text(mensaje, style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary, modifier = Modifier.padding(top = 4.dp))
                            TextButton(onClick = { vm.limpiarAviso() }, modifier = Modifier.padding(top = 4.dp)) {
                                Text("Reintentar")
                            }
                        }
                    }
                }

                AnonimoSection(vm.account)
            }
        }
    }
}
