package com.arkiv.player.ui.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import com.arkiv.player.pocketbase.AccountException
import com.arkiv.player.pocketbase.AccountManager
import com.arkiv.player.pocketbase.AccountState
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * Idioma TV: Surfaces focusables ([TvActionOption]) en vez de `Button`/`OutlinedButton`, inputs de
 * texto siguiendo el patrón de `TvAddScreen` (M3 `OutlinedTextField` estándar — tv.material3 no
 * trae un campo de texto propio), y un toggle de texto en vez de un ícono de ojo (más previsible
 * con mando/D-pad que un IconButton dentro de un campo). Misma funcionalidad que `AccountSection`
 * (móvil, `ui/settings/AccountSection.kt`): `Iniciar sesión` valida contra PocketBase, `Crear
 * cuenta` exige código de licencia y pasa por el gateway ([AccountManager.registrar]).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvSettingsCuenta(account: AccountManager, onVincularMagis: () -> Unit) {
    val state by account.state.collectAsStateWithLifecycle()

    Text("Cuenta", style = MaterialTheme.typography.titleMedium, color = Color.White)
    when (val s = state) {
        is AccountState.Conectado -> TvConectadoSection(account, s, onVincularMagis)
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
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
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
private fun TvConectadoSection(
    account: AccountManager,
    s: AccountState.Conectado,
    onVincularMagis: () -> Unit,
) {
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
        TvActionOption(label = "Vincular Magis", onClick = onVincularMagis)
    }
}
