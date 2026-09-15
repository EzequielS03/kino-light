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
import com.arkiv.player.data.magis.MagisAccount
import com.arkiv.player.data.magis.MagisAccountState
import com.arkiv.player.data.magis.MagisException
import kotlinx.coroutines.launch

/**
 * The phone's "Ajustes → Cuenta" (Task 8, sub-project 2B): the link with Magis, standing on its
 * own. There's no more Kino login/logout here -this screen stopped taking an `AccountManager`-;
 * all that's left is linking or unlinking Magis directly against [MagisAccount], with no Kino
 * account in between. Task 9 (sub-project 2B) took `AccountManager` and the whole Kino login
 * (`ui/entrada/`) away, so this file also lost `AnonimoSection` -its only caller was that screen-;
 * [PasswordField] stays below because [UnlinkedSection] still uses it for the Magis form.
 */
@Composable
internal fun AccountSection(account: MagisAccount) {
    val state by account.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { account.refresh() }

    Text("Cuenta", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))

    when (val s = state) {
        is MagisAccountState.Linked -> LinkedSection(account, s)
        MagisAccountState.None -> UnlinkedSection(account)
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
        // `PasswordVisualTransformation` masks what gets DRAWN, not what's exposed in the
        // accessibility tree: without this, the typed text comes out in the clear in a
        // `uiautomator dump` and to any installed accessibility service. Verified on the S24+ with
        // the password autofilled by the password manager -- it read out whole.
        //
        // When the password is in plain view (the eye icon), it isn't marked: there the person
        // already decided to show it, and marking it anyway would keep a screen reader from
        // dictating it.
        modifier = modifier.semantics { if (!visible) password() },
    )
}

@Composable
private fun LinkedSection(account: MagisAccount, state: MagisAccountState.Linked) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    Text("Xuper vinculado como ${state.email}", style = MaterialTheme.typography.bodyMedium)

    OutlinedButton(
        enabled = !busy,
        onClick = {
            scope.launch {
                busy = true
                // try/finally, not try/catch: unlink() doesn't throw -MagisSession.logout() never
                // throws, it returns a MagisResult-, but without the finally an unexpected
                // exception left the button stuck on "Desvinculando…" forever. Same pattern as the
                // TV (TvSettingsAccount.TvLinkedSection).
                try {
                    account.unlink()
                } finally {
                    busy = false
                }
            }
        },
        modifier = Modifier.padding(top = 8.dp),
    ) { Text(if (busy) "Desvinculando…" else "Desvincular Xuper") }
}

/** Sub-block for linking an already-existing Magis account -with no Kino account to pull the
 *  email from, so it starts blank (it used to come preloaded with the Kino email). */
@Composable
private fun UnlinkedSection(account: MagisAccount) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    if (!expanded) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.padding(top = 8.dp)) {
            Text("Vincular Xuper")
        }
        return
    }

    Column(Modifier.padding(top = 8.dp)) {
        OutlinedTextField(email, { email = it; error = null }, label = { Text("Email de Xuper") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth())
        PasswordField(password, { password = it; error = null }, "Contraseña de Xuper",
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        // "Registrar en Magis" is no longer here: creating the account needed the back-and-forth
        // of the email code, which the server used to orchestrate. Here an ALREADY existing
        // account gets linked.
        Text(
            "Tiene que ser una cuenta de Xuper que ya exista.",
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
                        account.link(email.trim(), password)
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
