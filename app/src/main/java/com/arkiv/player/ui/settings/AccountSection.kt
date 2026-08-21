package com.arkiv.player.ui.settings

import com.arkiv.player.ui.entrada.MascaraDeLicencia
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkiv.player.pocketbase.AccountException
import com.arkiv.player.pocketbase.AccountManager
import com.arkiv.player.pocketbase.AccountState
import kotlinx.coroutines.launch

/**
 * `Entrar` valida contra PocketBase (única fuente de identidad de una cuenta que ya existe);
 * `Crear cuenta` exige un código de licencia y pasa por el gateway ([AccountManager.registrar]).
 * Vincular Magis es un paso aparte y posterior, disponible una vez Conectado sin Magis
 * ([VincularMagisSection]) — el registro ya no lo intenta por su cuenta.
 */
@Composable
fun AccountSection(account: AccountManager) {
    val state by account.state.collectAsStateWithLifecycle()

    Text("Cuenta", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))

    when (val s = state) {
        is AccountState.Conectado -> ConectadoSection(account, s)
        AccountState.Anonimo -> AnonimoSection(account)
    }
}

@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Ocultar contraseña" else "Mostrar contraseña",
                )
            }
        },
        // `PasswordVisualTransformation` enmascara lo que se DIBUJA, no lo que se expone en el
        // árbol de accesibilidad: sin esto, el texto tipeado sale en claro en un `uiautomator
        // dump` y para cualquier servicio de accesibilidad instalado. Verificado en el S24+ con la
        // clave autocompletada por el gestor de contraseñas — se leía entera.
        //
        // Cuando la clave está a la vista (el ojito), no se marca: ahí la persona ya decidió
        // mostrarla, y marcarla igual haría que un lector de pantalla no pudiera dictarla.
        modifier = modifier.semantics { if (!visible) password() },
    )
}

/** `internal`, no `private`: `PantallaDeEntrada` (ui/entrada) la reusa tal cual para el mismo
 *  formulario de login/registro -si divergen, se arreglan bugs en uno y no en el otro-. */
@Composable
internal fun AnonimoSection(account: AccountManager) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var licencia by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Alterna entre "Entrar" (cuenta ya existente) y "Crear cuenta" (exige código de licencia):
    // son dos flujos distintos del gateway, no dos pasos del mismo.
    var registrando by remember { mutableStateOf(false) }

    OutlinedTextField(email, { email = it; error = null }, label = { Text("Email") },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth())
    PasswordField(password, { password = it; error = null }, "Contraseña",
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

    if (registrando) {
        // La licencia se formatea mientras se escribe: guiones automáticos, mayúsculas y los
        // caracteres ambiguos (O/0, I/1/L) corregidos en el acto. Ver MascaraDeLicencia.
        OutlinedTextField(
            licencia,
            { licencia = MascaraDeLicencia.formatear(it); error = null },
            label = { Text("Código de licencia") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }

    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }

    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (registrando) {
            Button(
                enabled = !busy && email.isNotBlank() && password.isNotBlank() && licencia.isNotBlank(),
                onClick = {
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
                },
            ) { Text(if (busy) "Creando…" else "Crear cuenta") }
            OutlinedButton(enabled = !busy, onClick = { registrando = false; error = null }) {
                Text("Ya tengo cuenta")
            }
        } else {
            Button(
                enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                onClick = {
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
                },
            ) { Text(if (busy) "Entrando…" else "Entrar") }
            OutlinedButton(
                enabled = !busy,
                onClick = { registrando = true; error = null },
            ) { Text("Crear cuenta") }
        }
    }
}

@Composable
private fun ConectadoSection(account: AccountManager, s: AccountState.Conectado) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(s.email) { account.refrescarMagis() }

    Text(
        "Conectado como ${s.email}" + if (s.magisLinked) " · Magis vinculado ✓" else "",
        style = MaterialTheme.typography.bodyMedium,
    )
    Button(
        enabled = !busy,
        onClick = { scope.launch { busy = true; runCatching { account.logout() }; busy = false } },
        modifier = Modifier.padding(top = 8.dp),
    ) { Text("Cerrar sesión") }

    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }

    if (s.magisLinked) {
        OutlinedButton(
            enabled = !busy,
            onClick = {
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
            },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text(if (busy) "Desvinculando…" else "Desvincular Magis") }
    } else {
        VincularMagisSection(account, s.email)
    }
}

/** Sub-bloque para vincular Magis a una cuenta Arkiv ya conectada que aún no lo tiene. */
@Composable
private fun VincularMagisSection(account: AccountManager, accountEmail: String) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf(accountEmail) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var codigoPedido by remember { mutableStateOf(false) }
    var codigo by remember { mutableStateOf("") }

    if (!expanded) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.padding(top = 8.dp)) {
            Text("Vincular Magis")
        }
        return
    }

    Column(Modifier.padding(top = 8.dp)) {
        OutlinedTextField(email, { email = it; error = null }, label = { Text("Email de Magis") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            enabled = !codigoPedido,
            modifier = Modifier.fillMaxWidth())
        PasswordField(password, { password = it; error = null }, "Contraseña de Magis",
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

        if (codigoPedido) {
            OutlinedTextField(codigo, { codigo = it; error = null }, label = { Text("Código") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Text(
                "Te enviamos un código a tu email. Si no aparece, revisa la carpeta de spam.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }

        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (codigoPedido) {
                Button(
                    enabled = !busy && codigo.isNotBlank(),
                    onClick = {
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
                    },
                ) { Text(if (busy) "Confirmando…" else "Confirmar") }
            } else {
                Button(
                    enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                    onClick = {
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
                    },
                ) { Text(if (busy) "Vinculando…" else "Vincular") }
                OutlinedButton(
                    enabled = !busy && email.isNotBlank(),
                    onClick = {
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
                    },
                ) { Text("Registrar en Magis") }
            }
        }
    }
}
