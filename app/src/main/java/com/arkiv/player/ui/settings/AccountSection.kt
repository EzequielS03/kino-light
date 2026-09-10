package com.arkiv.player.ui.settings

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
import com.arkiv.player.data.magis.CuentaDeMagis
import com.arkiv.player.data.magis.EstadoDeMagis
import com.arkiv.player.data.magis.MagisException
import com.arkiv.player.pocketbase.AccountException
import com.arkiv.player.pocketbase.AccountManager
import kotlinx.coroutines.launch

/**
 * "Ajustes → Cuenta" del celular (Task 8, sub-proyecto 2B): el vínculo con Magis, sobre sus propios
 * pies. Ya no hay login/logout de Kino acá -esta pantalla dejó de tomar un `AccountManager`-; lo
 * único que queda es vincular o desvincular Magis directo contra [CuentaDeMagis], sin ninguna
 * cuenta de Kino de por medio. [AnonimoSection] sigue abajo tal cual -esta pantalla ya no la llama,
 * pero [com.arkiv.player.ui.entrada.PantallaDeEntrada] sí, para su propio login de Kino-.
 */
@Composable
internal fun AccountSection(cuenta: CuentaDeMagis) {
    val estado by cuenta.estado.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { cuenta.refrescar() }

    Text("Cuenta", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))

    when (val e = estado) {
        is EstadoDeMagis.Vinculada -> VinculadaSection(cuenta, e)
        EstadoDeMagis.Sin -> SinVincularSection(cuenta)
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
 *  formulario de login -si divergen, se arreglan bugs en uno y no en el otro-. */
@Composable
internal fun AnonimoSection(account: AccountManager) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    OutlinedTextField(email, { email = it; error = null }, label = { Text("Email") },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth())
    PasswordField(password, { password = it; error = null }, "Contraseña",
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }

    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    }
}

@Composable
private fun VinculadaSection(cuenta: CuentaDeMagis, estado: EstadoDeMagis.Vinculada) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    Text("Magis vinculado como ${estado.email}", style = MaterialTheme.typography.bodyMedium)

    OutlinedButton(
        enabled = !busy,
        onClick = { scope.launch { busy = true; cuenta.desvincular(); busy = false } },
        modifier = Modifier.padding(top = 8.dp),
    ) { Text(if (busy) "Desvinculando…" else "Desvincular Magis") }
}

/** Sub-bloque para vincular una cuenta de Magis que ya exista -sin ninguna cuenta de Kino de la
 *  que sacar el email, así que arranca en blanco (antes venía precargado con el email de Kino). */
@Composable
private fun SinVincularSection(cuenta: CuentaDeMagis) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    if (!expanded) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.padding(top = 8.dp)) {
            Text("Vincular Magis")
        }
        return
    }

    Column(Modifier.padding(top = 8.dp)) {
        OutlinedTextField(email, { email = it; error = null }, label = { Text("Email de Magis") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth())
        PasswordField(password, { password = it; error = null }, "Contraseña de Magis",
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        // Ya no está "Registrar en Magis": crear la cuenta necesitaba el ida y vuelta del código
        // por email, que orquestaba el servidor. Acá se vincula una cuenta que YA existe.
        Text(
            "Tiene que ser una cuenta de Magis que ya exista.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }

        Button(
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.padding(top = 8.dp),
            onClick = {
                scope.launch {
                    busy = true
                    try {
                        cuenta.vincular(email.trim(), password)
                        expanded = false
                    } catch (e: MagisException) {
                        error = e.message
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text(if (busy) "Vinculando…" else "Vincular") }
    }
}
