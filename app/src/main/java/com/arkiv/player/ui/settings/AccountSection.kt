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
