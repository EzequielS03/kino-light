package com.arkiv.player.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkiv.player.pocketbase.AccountException
import com.arkiv.player.pocketbase.AccountManager
import com.arkiv.player.pocketbase.AccountState
import com.arkiv.player.pocketbase.RegistroPaso
import kotlinx.coroutines.launch

/**
 * Login unificado Magis-first: `Entrar` valida contra PocketBase o Magis (lo que reconozca la
 * cuenta), `Crear cuenta` pasa por el flujo de código de Magis (con fallback a cuenta sin Magis
 * si Magis está caído). Estando conectado sin Magis, se puede vincular después.
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
        modifier = modifier,
    )
}

@Composable
private fun AnonimoSection(account: AccountManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var codigoPedido by remember { mutableStateOf(false) }
    var codigo by remember { mutableStateOf("") }

    OutlinedTextField(email, { email = it; error = null }, label = { Text("Email") },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        enabled = !codigoPedido,
        modifier = Modifier.fillMaxWidth())
    PasswordField(password, { password = it; error = null }, "Contraseña",
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

    if (codigoPedido) {
        OutlinedTextField(codigo, { codigo = it; error = null }, label = { Text("Código") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Text(
            "Te enviamos un código a tu email. Si no aparece, revisá la carpeta de spam.",
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
                            account.registerConfirm(email.trim(), password, codigo.trim())
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
                enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                onClick = {
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
                },
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
                "Te enviamos un código a tu email. Si no aparece, revisá la carpeta de spam.",
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
