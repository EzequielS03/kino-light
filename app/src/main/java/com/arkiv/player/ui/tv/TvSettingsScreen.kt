package com.arkiv.player.ui.tv

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.RadioButtonDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import com.arkiv.player.data.Quality
import com.arkiv.player.data.WebQuality
import com.arkiv.player.data.update.UpdateInfo
import com.arkiv.player.pocketbase.AccountException
import com.arkiv.player.pocketbase.AccountManager
import com.arkiv.player.pocketbase.AccountState
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary
import com.arkiv.player.ui.update.UpdateDialog

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSettingsScreen(onConnectPhone: () -> Unit = {}) {
    val graph = rememberGraph()
    val settings = graph.settings
    val account = graph.accountManager
    val streamQuality by settings.streamQuality.collectAsStateWithLifecycle()
    val webQuality by settings.webQuality.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checkingUpdate by remember { mutableStateOf(false) }
    var manualUpdate by remember { mutableStateOf<UpdateInfo?>(null) }

    // Chequeo manual: independiente del diálogo global de MainActivity, así funciona aunque
    // este último ya haya sido descartado por el usuario en esta sesión.
    fun checkForUpdatesNow() {
        checkingUpdate = true
        scope.launch {
            graph.checkForUpdate()
            checkingUpdate = false
            val info = graph.updateInfo.value
            if (info != null) {
                manualUpdate = info
            } else {
                Toast.makeText(context, "Ya tienes la última versión", Toast.LENGTH_SHORT).show()
            }
        }
    }

    manualUpdate?.let { info ->
        UpdateDialog(info = info, graph = graph, onDismiss = { manualUpdate = null })
    }

    // Calidad web: persiste local + sincroniza al otro dispositivo (celular/TV).
    fun setWebQuality(q: WebQuality) {
        settings.setWebQuality(q)
        graph.applicationScope.launch { runCatching { graph.remoteController.sendWebQuality(q.name) } }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(64.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Ajustes", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Text("Calidad al reproducir", style = MaterialTheme.typography.titleMedium, color = Color.White)
        TvQualityOption("Original (máxima calidad, mkv)", Quality.ORIGINAL, streamQuality) {
            settings.setStreamQuality(Quality.ORIGINAL)
        }
        TvQualityOption("Liviano (mp4, ahorra datos)", Quality.DERIVATIVE, streamQuality) {
            settings.setStreamQuality(Quality.DERIVATIVE)
        }
        Text("Calidad de fuentes web", style = MaterialTheme.typography.titleMedium, color = Color.White)
        TvWebQualityOption("Auto (recomendado: HD si la conexión da, si no SD)", WebQuality.AUTO, webQuality) {
            setWebQuality(WebQuality.AUTO)
        }
        TvWebQualityOption("SD · 480p (máxima fluidez)", WebQuality.SD, webQuality) {
            setWebQuality(WebQuality.SD)
        }
        TvWebQualityOption("HD · hasta 720p", WebQuality.HD, webQuality) {
            setWebQuality(WebQuality.HD)
        }
        TvWebQualityOption("Máx · la más alta disponible", WebQuality.MAX, webQuality) {
            setWebQuality(WebQuality.MAX)
        }
        Text("Teléfono", style = MaterialTheme.typography.titleMedium, color = Color.White)
        TvActionOption("Conectar teléfono", onConnectPhone)
        Text("Cuenta", style = MaterialTheme.typography.titleMedium, color = Color.White)
        TvAccountSection(account)
        Text("Actualizaciones", style = MaterialTheme.typography.titleMedium, color = Color.White)
        TvActionOption(
            if (checkingUpdate) "Buscando…" else "Buscar actualizaciones",
            onClick = { if (!checkingUpdate) checkForUpdatesNow() },
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvWebQualityOption(label: String, value: WebQuality, selected: WebQuality, onSelect: () -> Unit) {
    val isSelected = selected == value
    Surface(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(0.6f),
        shape = ClickableSurfaceDefaults.shape(
            androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ArkivSurfaceHigh,
            contentColor = Color.White,
            focusedContainerColor = ArkivRed,
            focusedContentColor = Color.White,
            pressedContainerColor = ArkivRed,
            pressedContentColor = Color.White,
        ),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(selectedColor = Color.White, unselectedColor = Color.White),
            )
            Text(label, color = Color.White, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvActionOption(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(0.6f),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
            androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        ),
        // Los colores van explícitos, como en el resto de la UI de TV: `ClickableSurfaceDefaults`
        // los saca del tema de tv-material3, y la app nunca envuelve nada en un
        // `androidx.tv.material3.MaterialTheme` (usa el M3 normal, ver ui/theme/Theme.kt), así que
        // sin esto el Surface caía en el esquema claro por defecto de la librería —fondo casi
        // blanco— y el texto blanco de abajo quedaba invisible: los botones se veían "en blanco".
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ArkivSurfaceHigh,
            contentColor = Color.White,
            focusedContainerColor = ArkivRed,
            focusedContentColor = Color.White,
            pressedContainerColor = ArkivRed,
            pressedContentColor = Color.White,
        ),
    ) {
        Text(label, color = Color.White, modifier = Modifier.padding(16.dp))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvQualityOption(label: String, value: Quality, selected: Quality, onSelect: () -> Unit) {
    val isSelected = selected == value
    Surface(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(0.6f),
        shape = ClickableSurfaceDefaults.shape(
            androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ArkivSurfaceHigh,
            contentColor = Color.White,
            focusedContainerColor = ArkivRed,
            focusedContentColor = Color.White,
            pressedContainerColor = ArkivRed,
            pressedContentColor = Color.White,
        ),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(
                    selectedColor = Color.White,
                    unselectedColor = Color.White,
                ),
            )
            Text(label, color = Color.White, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

/**
 * Misma funcionalidad que `AccountSection` (móvil, `ui/settings/AccountSection.kt`) pero en el
 * idioma TV: Surfaces focusables (`TvActionOption`) en vez de `Button`/`OutlinedButton`, y los
 * inputs de texto siguiendo el patrón de `TvAddScreen` (M3 `OutlinedTextField` estándar — tv.material3
 * no trae un campo de texto propio).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvAccountSection(account: AccountManager) {
    val state by account.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    when (val s = state) {
        is AccountState.Conectado -> {
            Text("Conectado como ${s.email}", color = Color.White)
            TvActionOption(
                label = if (busy) "Cerrando sesión…" else "Cerrar sesión",
                onClick = {
                    if (!busy) {
                        scope.launch {
                            busy = true
                            runCatching { account.logout() }
                            busy = false
                        }
                    }
                },
            )
        }
        AccountState.Anonimo -> {
            // `dpadFocusEscape` + `imeAction` son lo que hace usable el formulario con el control:
            // sin el primero el foco queda atrapado en el campo (arriba/abajo los come el cursor
            // del TextField) y sin el segundo el teclado del Fire TV cierra sobre el mismo campo
            // en vez de avanzar. Ver [dpadFocusEscape] en TvComponents.kt.
            OutlinedTextField(
                value = email,
                onValueChange = { email = it; error = null },
                label = { androidx.compose.material3.Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
                modifier = Modifier.fillMaxWidth(0.6f).dpadFocusEscape(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = { androidx.compose.material3.Text("Contraseña") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                // Al cerrar el teclado el foco baja a "Iniciar sesión", que es lo siguiente que
                // uno quiere apretar. `clearFocus()` dejaría el D-pad sin nada enfocado.
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.moveFocus(FocusDirection.Down) },
                ),
                modifier = Modifier.fillMaxWidth(0.6f).padding(top = 8.dp).dpadFocusEscape(),
            )
            error?.let {
                Text(it, color = ArkivRed, modifier = Modifier.padding(top = 6.dp))
            }
            fun run(op: suspend (String, String) -> Unit) {
                if (busy || email.isBlank() || password.isBlank()) return
                scope.launch {
                    busy = true
                    try {
                        op(email.trim(), password)
                    } catch (e: AccountException) {
                        error = e.message
                    }
                    busy = false
                }
            }
            TvActionOption(
                label = if (busy) "Espere…" else "Iniciar sesión",
                onClick = { run(account::login) },
            )
            TvActionOption(
                label = if (busy) "Espere…" else "Crear cuenta",
                onClick = { run(account::register) },
            )
            if (busy) {
                Text("Procesando…", color = ArkivTextSecondary, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
