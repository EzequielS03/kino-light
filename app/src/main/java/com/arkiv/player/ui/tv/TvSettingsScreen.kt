package com.arkiv.player.ui.tv

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
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
import com.arkiv.player.pocketbase.RegistroPaso
import com.arkiv.player.ui.rememberGraph
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

// Estilo único de los botones de Ajustes (TvWebQualityOption/TvActionOption/TvQualityOption):
// inactivo = negro + borde blanco 1dp; enfocado/presionado = fondo rojo Arkiv, sin borde blanco.
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun tvBotonColors() = ClickableSurfaceDefaults.colors(
    containerColor = Color.Black,
    focusedContainerColor = ArkivRed,
    pressedContainerColor = ArkivRed,
    contentColor = Color.White,
    focusedContentColor = Color.White,
    pressedContentColor = Color.White,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun tvBotonBorder() = ClickableSurfaceDefaults.border(
    border = Border(BorderStroke(1.dp, Color.White)),
    focusedBorder = Border(BorderStroke(1.dp, ArkivRed)),
    pressedBorder = Border(BorderStroke(1.dp, ArkivRed)),
)

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
        // Sin colores explícitos el Surface de tv.material3 usa el color por defecto (claro):
        // el botón se veía BLANCO. Superficie negra + borde blanco inactivo, rojo Arkiv al
        // enfocar/presionar (para que el D-pad muestre dónde está el foco), texto blanco siempre.
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
 * Login unificado Magis-first, idioma TV: Surfaces focusables (`TvActionOption`) en vez de
 * `Button`/`OutlinedButton`, inputs de texto siguiendo el patrón de `TvAddScreen` (M3
 * `OutlinedTextField` estándar — tv.material3 no trae un campo de texto propio), y un toggle de
 * texto en vez de un ícono de ojo (más previsible con mando/D-pad que un IconButton dentro de un
 * campo). Misma funcionalidad que `AccountSection` (móvil, `ui/settings/AccountSection.kt`).
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
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { androidx.compose.material3.Text(label) },
            singleLine = true,
            visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var codigoPedido by remember { mutableStateOf(false) }
    var codigo by remember { mutableStateOf("") }

    OutlinedTextField(
        value = email,
        onValueChange = { email = it; error = null },
        label = { androidx.compose.material3.Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        enabled = !codigoPedido,
        modifier = Modifier.fillMaxWidth(0.6f),
    )
    TvPasswordField(password, { password = it; error = null }, "Contraseña", modifier = Modifier.fillMaxWidth(0.6f).padding(top = 8.dp))

    if (codigoPedido) {
        OutlinedTextField(
            value = codigo,
            onValueChange = { codigo = it; error = null },
            label = { androidx.compose.material3.Text("Código") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(0.6f).padding(top = 8.dp),
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
                            account.registerConfirm(email.trim(), password, codigo.trim())
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
            label = if (busy) "Espere…" else "Crear cuenta",
            onClick = {
                if (!busy && email.isNotBlank() && password.isNotBlank()) {
                    scope.launch {
                        busy = true
                        try {
                            when (account.registerSendCode(email.trim(), password)) {
                                RegistroPaso.CODIGO_ENVIADO -> codigoPedido = true
                                RegistroPaso.CREADA_SIN_MAGIS ->
                                    Toast.makeText(context, "Magis no disponible; podés vincularlo después", Toast.LENGTH_LONG).show()
                            }
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
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            enabled = !codigoPedido,
            modifier = Modifier.fillMaxWidth(0.6f),
        )
        TvPasswordField(password, { password = it; error = null }, "Contraseña de Magis", modifier = Modifier.fillMaxWidth(0.6f).padding(top = 8.dp))

        if (codigoPedido) {
            OutlinedTextField(
                value = codigo,
                onValueChange = { codigo = it; error = null },
                label = { androidx.compose.material3.Text("Código") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(0.6f).padding(top = 8.dp),
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
