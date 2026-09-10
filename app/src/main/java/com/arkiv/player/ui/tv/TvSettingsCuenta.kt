package com.arkiv.player.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arkiv.player.data.magis.CuentaDeMagis
import com.arkiv.player.data.magis.EstadoDeMagis
import kotlinx.coroutines.launch

/**
 * "Ajustes → Cuenta" de la TV (Task 8, sub-proyecto 2B): igual que la del celular
 * (`ui/settings/AccountSection.kt`), solo el vínculo con Magis. Ya no hay login de Kino acá -eso
 * vivía en `TvAnonimoSection`/`TvConectadoSection` (con `AccountManager`), que se borraron en esta
 * misma tarea porque no tenían otro llamador: `TvPantallaDeEntrada.kt` arma su propio formulario de
 * login (`PanelDeLogin`) sin pasar por acá-.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvSettingsCuenta(cuenta: CuentaDeMagis, onVincularMagis: () -> Unit) {
    val estado by cuenta.estado.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { cuenta.refrescar() }

    Text("Cuenta", style = MaterialTheme.typography.titleMedium, color = Color.White)
    when (val e = estado) {
        is EstadoDeMagis.Vinculada -> TvVinculadaSection(cuenta, e)
        EstadoDeMagis.Sin -> TvActionOption(label = "Vincular Magis", onClick = onVincularMagis)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvVinculadaSection(cuenta: CuentaDeMagis, estado: EstadoDeMagis.Vinculada) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    Text("Magis vinculado como ${estado.email}", color = Color.White)
    TvActionOption(
        label = if (busy) "Desvinculando…" else "Desvincular Magis",
        onClick = {
            if (!busy) {
                scope.launch {
                    busy = true
                    // try/finally, no try/catch: desvincular() no lanza -MagisSession.logout() nunca
                    // tira, devuelve MagisResult-, así que un catch(MagisException) acá sería
                    // inalcanzable. Mismo patrón que el celu (AccountSection.VinculadaSection).
                    try {
                        cuenta.desvincular()
                    } finally {
                        busy = false
                    }
                }
            }
        },
    )
}
