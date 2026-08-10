package com.arkiv.player.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkiv.player.pocketbase.AccountManager
import com.arkiv.player.pocketbase.AccountException
import com.arkiv.player.pocketbase.AccountState
import com.arkiv.player.pocketbase.MagisLinkClient
import com.arkiv.player.pocketbase.MagisLinkException
import com.arkiv.player.ui.rememberGraph
import kotlinx.coroutines.launch

@Composable
fun AccountSection(account: AccountManager) {
    val state by account.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Text("Cuenta", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))

    when (val s = state) {
        is AccountState.Conectado -> {
            Text("Conectado como ${s.email}", style = MaterialTheme.typography.bodyMedium)
            Button(
                enabled = !busy,
                onClick = { scope.launch { busy = true; runCatching { account.logout() }; busy = false } },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Cerrar sesión") }

            MagisSection(rememberGraph().magisLinkClient)
        }
        AccountState.Anonimo -> {
            OutlinedTextField(email, { email = it; error = null }, label = { Text("Email") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it; error = null }, label = { Text("Contraseña") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fun run(op: suspend (String, String) -> Unit) = scope.launch {
                    busy = true
                    try { op(email.trim(), password) } catch (e: AccountException) { error = e.message }
                    busy = false
                }
                Button(enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                    onClick = { run(account::login) }) { Text("Iniciar sesión") }
                OutlinedButton(enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                    onClick = { run(account::register) }) { Text("Crear cuenta") }
            }
        }
    }
}

/**
 * Sub-bloque "Magis" dentro de la cuenta conectada: vincular/desvincular la cuenta de Magis con
 * la cuenta Arkiv del usuario (ver `MagisLinkClient`). Solo tiene sentido con sesión Arkiv activa,
 * por eso se llama desde el branch `AccountState.Conectado`.
 */
@Composable
private fun MagisSection(client: MagisLinkClient) {
    val scope = rememberCoroutineScope()
    var linked by remember { mutableStateOf<Boolean?>(null) } // null = todavía consultando
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(client) {
        linked = runCatching { client.status() }.getOrDefault(false)
    }

    Text("Magis", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))

    when (linked) {
        null -> Text("Consultando…", style = MaterialTheme.typography.bodySmall)
        true -> {
            Text("Vinculado", style = MaterialTheme.typography.bodyMedium)
            Button(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        try { client.unlink(); linked = false } catch (e: MagisLinkException) { error = e.message } finally { busy = false }
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Desvincular") }
        }
        else -> {
            OutlinedTextField(user, { user = it; error = null }, label = { Text("Usuario de Magis") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it; error = null }, label = { Text("Clave de Magis") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            Button(
                enabled = !busy && user.isNotBlank() && pass.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        try { client.link(user.trim(), pass); linked = true } catch (e: MagisLinkException) { error = e.message } finally { busy = false }
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Vincular Magis") }
        }
    }
}
