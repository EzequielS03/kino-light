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
import androidx.compose.runtime.LaunchedEffect
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
import com.arkiv.player.data.subtitles.PlaybackPrefs
import com.arkiv.player.data.subtitles.SubtitleMode
import com.arkiv.player.data.update.UpdateInfo
import com.arkiv.player.pocketbase.AccountException
import com.arkiv.player.pocketbase.AccountManager
import com.arkiv.player.pocketbase.AccountState
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.settings.IDIOMAS_AUDIO
import com.arkiv.player.ui.settings.IDIOMAS_SUBTITULO
import com.arkiv.player.ui.theme.ArkivRed
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
    val playbackPrefs by graph.subtitlePrefs.prefs.collectAsStateWithLifecycle()

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

    // Persiste local + sincroniza al celular, igual que hace la pantalla de Ajustes del teléfono.
    fun setPrefs(p: PlaybackPrefs) {
        graph.subtitlePrefs.update(p)
        graph.applicationScope.launch { runCatching { graph.remoteController.sendSubtitlePrefs(p.toJson()) } }
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
        Text(
            "Audio y subtítulos",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.padding(top = 24.dp),
        )
        TvLanguageOrderEditor(
            title = "Idioma del audio (en orden de preferencia)",
            options = IDIOMAS_AUDIO,
            order = playbackPrefs.audioLangs,
            onChange = { setPrefs(playbackPrefs.copy(audioLangs = it)) },
        )
        TvLanguageChecklist(
            title = "Idiomas que entiendo",
            subtitle = "Los subtítulos se prenden solos únicamente cuando el audio queda en un " +
                "idioma que no está en esta lista.",
            options = IDIOMAS_AUDIO,
            selected = playbackPrefs.understoodLangs,
            onChange = { setPrefs(playbackPrefs.copy(understoodLangs = it)) },
        )
        TvLanguageOrderEditor(
            title = "Idioma de los subtítulos (en orden de preferencia)",
            options = IDIOMAS_SUBTITULO,
            order = playbackPrefs.subtitleLangs,
            onChange = { setPrefs(playbackPrefs.copy(subtitleLangs = it)) },
        )
        TvActionOption(
            if (playbackPrefs.subtitleMode == SubtitleMode.AUTO) {
                "Subtítulos: automáticos (tocá para desactivar)"
            } else {
                "Subtítulos: desactivados (tocá para automáticos)"
            },
        ) {
            val nuevo = if (playbackPrefs.subtitleMode == SubtitleMode.AUTO) {
                SubtitleMode.OFF
            } else {
                SubtitleMode.AUTO
            }
            setPrefs(playbackPrefs.copy(subtitleMode = nuevo))
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

// Estilo único de los botones de Ajustes (TvWebQualityOption/TvActionOption/TvQualityOption):
// inactivo = negro + borde blanco 1dp; enfocado/presionado = fondo rojo Arkiv, sin borde blanco.
// Delega al estilo compartido de botones-acción de TV (TvButtonStyle.kt) para que Ajustes no se
// desincronice del resto de la app.
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun tvBotonColors() = arkivTvSurfaceColors()

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun tvBotonBorder() = arkivTvSurfaceBorder()

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
        colors = tvBotonColors(),
        border = tvBotonBorder(),
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
        // Superficie negra + borde blanco inactivo, rojo Arkiv al enfocar/presionar (estándar
        // compartido en TvButtonStyle.kt). Sin colores explícitos el Surface de tv.material3 cae
        // en el esquema claro por defecto de la librería y el botón se veía BLANCO.
        colors = tvBotonColors(),
        border = tvBotonBorder(),
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
        colors = tvBotonColors(),
        border = tvBotonBorder(),
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
 * Idioma TV: Surfaces focusables (`TvActionOption`) en vez de `Button`/`OutlinedButton`, inputs de
 * texto siguiendo el patrón de `TvAddScreen` (M3 `OutlinedTextField` estándar — tv.material3 no
 * trae un campo de texto propio), y un toggle de texto en vez de un ícono de ojo (más previsible
 * con mando/D-pad que un IconButton dentro de un campo). Misma funcionalidad que `AccountSection`
 * (móvil, `ui/settings/AccountSection.kt`): `Iniciar sesión` valida contra PocketBase, `Crear
 * cuenta` exige código de licencia y pasa por el gateway ([AccountManager.registrar]).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvAccountSection(account: AccountManager) {
    val state by account.state.collectAsStateWithLifecycle()

    when (val s = state) {
        is AccountState.Conectado -> TvConectadoSection(account, s)
        AccountState.Anonimo -> TvAnonimoSection(account)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvPasswordField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { androidx.compose.material3.Text(label) },
            singleLine = true,
            visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            // imeAction + dpadFocusEscape: sin el escape el D-pad queda atrapado en el campo (arriba/
            // abajo los come el cursor) y el teclado del Fire TV cerraría sobre el mismo campo. Ver
            // [dpadFocusEscape] en TvComponents.kt.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.moveFocus(FocusDirection.Down) }),
            modifier = Modifier.fillMaxWidth().dpadFocusEscape(),
        )
        TvActionOption(
            label = if (visible) "Ocultar contraseña" else "Mostrar contraseña",
            onClick = { visible = !visible },
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvAnonimoSection(account: AccountManager) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var licencia by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Alterna entre "Iniciar sesión" (cuenta ya existente) y "Crear cuenta" (exige código de
    // licencia): son dos flujos distintos del gateway, no dos pasos del mismo.
    var registrando by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = email,
        onValueChange = { email = it; error = null },
        label = { androidx.compose.material3.Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
        modifier = Modifier.fillMaxWidth(0.6f).dpadFocusEscape(),
    )
    TvPasswordField(password, { password = it; error = null }, "Contraseña", modifier = Modifier.fillMaxWidth(0.6f).padding(top = 8.dp))

    if (registrando) {
        OutlinedTextField(
            value = licencia,
            onValueChange = { licencia = it; error = null },
            label = { androidx.compose.material3.Text("Código de licencia") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.moveFocus(FocusDirection.Down) }),
            modifier = Modifier.fillMaxWidth(0.6f).padding(top = 8.dp).dpadFocusEscape(),
        )
    }

    error?.let {
        Text(it, color = ArkivRed, modifier = Modifier.padding(top = 6.dp))
    }

    if (registrando) {
        TvActionOption(
            label = if (busy) "Creando…" else "Crear cuenta",
            onClick = {
                if (!busy && email.isNotBlank() && password.isNotBlank() && licencia.isNotBlank()) {
                    scope.launch {
                        busy = true
                        try {
                            account.registrar(email.trim(), password, licencia.trim())
                        } catch (e: AccountException) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                }
            },
        )
        TvActionOption(label = "Ya tengo cuenta", onClick = { registrando = false; error = null })
    } else {
        TvActionOption(
            label = if (busy) "Espere…" else "Iniciar sesión",
            onClick = {
                if (!busy && email.isNotBlank() && password.isNotBlank()) {
                    scope.launch {
                        busy = true
                        try {
                            account.login(email.trim(), password)
                        } catch (e: AccountException) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                }
            },
        )
        TvActionOption(
            label = "Crear cuenta",
            onClick = { registrando = true; error = null },
        )
    }
    if (busy) {
        Text("Procesando…", color = ArkivTextSecondary, modifier = Modifier.padding(top = 4.dp))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvConectadoSection(account: AccountManager, s: AccountState.Conectado) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(s.email) { account.refrescarMagis() }

    Text("Conectado como ${s.email}" + if (s.magisLinked) " · Magis vinculado ✓" else "", color = Color.White)
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

    error?.let {
        Text(it, color = ArkivRed, modifier = Modifier.padding(top = 6.dp))
    }

    if (s.magisLinked) {
        TvActionOption(
            label = if (busy) "Desvinculando…" else "Desvincular Magis",
            onClick = {
                if (!busy) {
                    scope.launch {
                        busy = true
                        try {
                            account.desvincularMagis()
                        } catch (e: AccountException) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                }
            },
        )
    } else {
        TvVincularMagisSection(account, s.email)
    }
}

/** Sub-bloque para vincular Magis a una cuenta Arkiv ya conectada que aún no lo tiene (equivalente
 *  TV de `VincularMagisSection` en `ui/settings/AccountSection.kt`). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvVincularMagisSection(account: AccountManager, accountEmail: String) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var expanded by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf(accountEmail) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var codigoPedido by remember { mutableStateOf(false) }
    var codigo by remember { mutableStateOf("") }

    if (!expanded) {
        TvActionOption(label = "Vincular Magis", onClick = { expanded = true })
        return
    }

    Column(Modifier.padding(top = 8.dp)) {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it; error = null },
            label = { androidx.compose.material3.Text("Email de Magis") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            enabled = !codigoPedido,
            modifier = Modifier.fillMaxWidth(0.6f).dpadFocusEscape(),
        )
        TvPasswordField(password, { password = it; error = null }, "Contraseña de Magis", modifier = Modifier.fillMaxWidth(0.6f).padding(top = 8.dp))

        if (codigoPedido) {
            OutlinedTextField(
                value = codigo,
                onValueChange = { codigo = it; error = null },
                label = { androidx.compose.material3.Text("Código") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth(0.6f).padding(top = 8.dp).dpadFocusEscape(),
            )
            Text(
                "Te enviamos un código a tu email. Si no aparece, revisá la carpeta de spam.",
                color = ArkivTextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        error?.let {
            Text(it, color = ArkivRed, modifier = Modifier.padding(top = 6.dp))
        }

        if (codigoPedido) {
            TvActionOption(
                label = if (busy) "Confirmando…" else "Confirmar",
                onClick = {
                    if (!busy && codigo.isNotBlank()) {
                        scope.launch {
                            busy = true
                            try {
                                account.vincularMagisConfirmar(email.trim(), password, codigo.trim())
                                expanded = false
                            } catch (e: AccountException) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
            )
        } else {
            TvActionOption(
                label = if (busy) "Vinculando…" else "Vincular",
                onClick = {
                    if (!busy && email.isNotBlank() && password.isNotBlank()) {
                        scope.launch {
                            busy = true
                            try {
                                account.vincularMagis(email.trim(), password)
                                expanded = false
                            } catch (e: AccountException) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
            )
            TvActionOption(
                label = if (busy) "Espere…" else "Registrar en Magis",
                onClick = {
                    if (!busy && email.isNotBlank()) {
                        scope.launch {
                            busy = true
                            try {
                                account.vincularMagisEnviarCodigo(email.trim())
                                codigoPedido = true
                            } catch (e: AccountException) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
            )
        }
        if (busy) {
            Text("Procesando…", color = ArkivTextSecondary, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
