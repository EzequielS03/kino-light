package com.arkiv.player.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.data.credentials.CredentialsActivator
import com.arkiv.player.data.credentials.RemoteCredentialsStore
import com.arkiv.player.ui.theme.ArkivRed
import kotlinx.coroutines.launch

private sealed interface TvActivationUiState {
    data object Idle : TvActivationUiState
    data object Loading : TvActivationUiState
    data object Failed : TvActivationUiState
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvActivationScreen(
    activator: CredentialsActivator,
    store: RemoteCredentialsStore,
    onActivated: () -> Unit,
) {
    var state by remember { mutableStateOf<TvActivationUiState>(TvActivationUiState.Idle) }
    val scope = rememberCoroutineScope()

    fun activate() {
        state = TvActivationUiState.Loading
        scope.launch {
            val credentials = activator.activate()
            if (credentials != null) {
                store.save(credentials)
                onActivated()
            } else {
                state = TvActivationUiState.Failed
            }
        }
    }

    Box(Modifier.fillMaxSize().padding(1.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth(0.6f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Para funcionar, Kino necesita traer credenciales de terceros a este dispositivo. " +
                    "Al activar, entiendes que lo haces bajo tu propia responsabilidad, y que la app " +
                    "no es responsable por su uso.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            when (state) {
                is TvActivationUiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.padding(top = 24.dp),
                )
                is TvActivationUiState.Failed -> {
                    Text(
                        "Algo salió mal, intenta de nuevo.",
                        color = ArkivRed,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    TvActivationButton("Reintentar", onClick = ::activate)
                }
                is TvActivationUiState.Idle -> TvActivationButton("Activar", onClick = ::activate)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvActivationButton(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.height(48.dp).padding(top = 24.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 24.dp))
        }
    }
}
