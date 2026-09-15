package com.arkiv.player.ui.tv

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import kotlinx.coroutines.delay
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
    val activity = LocalContext.current as? Activity
    // "Cerrar" gets default focus, not "Activar" -- a deliberate choice: activating is the more
    // consequential of the two actions, so the remote shouldn't land on it by accident. Requested
    // explicitly on every entry into Idle/Failed since each is a different Row (a fresh Surface),
    // not something Compose's own initial-focus heuristic can be relied on to pick consistently.
    val closeFocusRequester = remember { FocusRequester() }
    LaunchedEffect(state) {
        if (state !is TvActivationUiState.Idle && state !is TvActivationUiState.Failed) return@LaunchedEffect
        // requestFocus() throws until the target Surface has actually been placed -- same retry
        // shape as TvKeyboardAndFields' firstFieldFocus, the proven pattern elsewhere in this app.
        repeat(20) {
            if (runCatching { closeFocusRequester.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
            delay(50)
        }
    }

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
                "Kino conecta con servicios de terceros para poder funcionar, no aloja contenido propio. " +
                    "No apoyamos la piratería: te invitamos siempre a ver películas y series por canales " +
                    "legales. Al activar, entiendes que decides usar estos servicios bajo tu propia " +
                    "responsabilidad, y que la app no responde por su uso.",
                style = MaterialTheme.typography.bodyLarge,
                color = androidx.compose.ui.graphics.Color.White,
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        TvActivationButton("Reintentar", onClick = ::activate)
                        TvActivationButton(
                            "Cerrar",
                            onClick = { activity?.finishAndRemoveTask() },
                            modifier = Modifier.focusRequester(closeFocusRequester),
                        )
                    }
                }
                is TvActivationUiState.Idle -> Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 24.dp),
                ) {
                    TvActivationButton("Activar", onClick = ::activate)
                    TvActivationButton(
                        "Cerrar",
                        onClick = { activity?.finishAndRemoveTask() },
                        modifier = Modifier.focusRequester(closeFocusRequester),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvActivationButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(48.dp).width(160.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = arkivTvSurfaceColors(),
        border = arkivTvSurfaceBorder(),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
}
