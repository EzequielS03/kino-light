package com.arkiv.player.ui.tv

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.data.update.UpdateInfo
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.settings.AdultsLock
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary
import com.arkiv.player.ui.update.UpdateDialog

/** What belongs to the app on this TV: updates and the 18+ lock. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvSettingsApp() {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var manualUpdate by remember { mutableStateOf<UpdateInfo?>(null) }

    // Manual check: independent of MainActivity's global dialog, so it works even if the user
    // already dismissed that one this session.
    fun checkForUpdatesNow() {
        checking = true
        scope.launch {
            graph.checkForUpdate()
            checking = false
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

    Text("Actualizaciones", style = MaterialTheme.typography.titleMedium, color = Color.White)
    TvActionOption(
        if (checking) "Buscando…" else "Buscar actualizaciones",
        onClick = { if (!checking) checkForUpdatesNow() },
    )

    TvAdultsSection(graph.settings)
}

/**
 * The 18+ section's lock.
 *
 * Unlocked, it shows ONE row asking for a code, and nothing else: not the section's name, not a
 * grayed-out button, not a lock icon. Announcing that something exists is half the problem —
 * whoever doesn't know the code has no reason to learn there's a door.
 *
 * Unlocks ONLY this device ([SettingsStore.setAdultsUnlocked] goes to the device's settings,
 * not the account): the living-room TV doesn't inherit what was unlocked on the phone, and
 * uninstalling the app turns it off.
 *
 * What unlocking does is make the app request categories with `includeAdults = true`; the
 * client itself (`MagisLiveCatalog`) filters them out by default otherwise. So `18+` shows up as
 * just another category in the Live guide and the channel drawer, exactly where the portal puts
 * it.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvAdultsSection(store: SettingsStore) {
    var unlocked by remember { mutableStateOf(store.adultsUnlocked.value) }
    var saved by remember { mutableStateOf(store.adultsCode.value) }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    if (AdultsLock.shouldShowSection(unlocked)) {
        Text("Adultos", style = MaterialTheme.typography.titleMedium, color = Color.White)
        Text(
            "La categoría 18+ está visible en En vivo y en el cajón de canales de este aparato.",
            style = MaterialTheme.typography.bodySmall,
            color = ArkivTextSecondary,
        )
        TvActionOption(label = "Ocultar 18+ en este aparato") {
            store.setAdultsUnlocked(false)
            unlocked = false
            code = ""
        }
        TvChangeAdultsCode(store) { saved = it }
        return
    }
    if (!AdultsLock.shouldShowField(unlocked)) return

    val focusManager = LocalFocusManager.current

    fun attempt() {
        // The reset is checked BEFORE unlocking: it's the way out for whoever forgot the code
        // they set, and that's why there's no other hint that it exists. The signal that it
        // worked is that the default-code notice reappears on its own.
        if (AdultsLock.requestsReset(code)) {
            store.setAdultsCode(null)
            saved = null
            code = ""
            error = false
            return
        }
        if (AdultsLock.unlocks(code, AdultsLock.effectiveCode(saved))) {
            store.setAdultsUnlocked(true)
            unlocked = true
            error = false
        } else {
            error = true
        }
    }

    // Not labeled as "adults": the row just says "Código" and nothing else.
    Text("Código", style = MaterialTheme.typography.titleMedium, color = Color.White)
    // As long as the code is the one anyone knows, it's shown. That's what makes the section
    // usable by whoever installs the APK without having built it themselves; it disappears once
    // a real code is set.
    if (AdultsLock.isDefault(saved)) {
        Text(
            "Por defecto: ${AdultsLock.DEFAULT_CODE}",
            style = MaterialTheme.typography.bodySmall,
            color = ArkivTextSecondary,
        )
    }
    OutlinedTextField(
        value = code,
        onValueChange = { code = it; error = false },
        singleLine = true,
        // `Done` that APPLIES, not one that only closes the keyboard. On a TV, closing the IME
        // leaves focus trapped in the field -- the D-pad won't release it and the button below is
        // unreachable. This applies the code without having to leave the field, which is the
        // natural path: finish typing and confirm on the same keyboard.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { attempt(); focusManager.moveFocus(FocusDirection.Down) }),
        // And the emergency exit: down leaves the field even if the IME doesn't cooperate.
        // Without this, a keyboard that closes without firing `onDone` leaves focus locked in
        // with no way to reach the button with the remote.
        modifier = Modifier
            .fillMaxWidth(0.4f)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                    focusManager.moveFocus(FocusDirection.Down)
                    true
                } else {
                    false
                }
            },
    )
    if (error) {
        Text("Código incorrecto", style = MaterialTheme.typography.bodySmall, color = ArkivRed)
    }
    TvActionOption(label = "Aplicar código") { attempt() }
}

/**
 * Change the code, only from inside the already-unlocked section. Doesn't ask for the current
 * code: getting here already required typing it.
 *
 * [onSave] tells [TvAdultsSection] what it ended up being, so its "default" notice reflects the
 * change without re-reading the store.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvChangeAdultsCode(store: SettingsStore, onSave: (String) -> Unit) {
    var newCode by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    fun save() {
        val trimmed = newCode.trim()
        when {
            !AdultsLock.isValidFormat(trimmed) -> {
                message = "Usa 4 dígitos"
                done = false
            }
            // Without explaining why: saying "that's the reset one" would announce the exit the
            // reset exists to not announce.
            AdultsLock.isReserved(trimmed) -> {
                message = "Ese código no está disponible, elige otro"
                done = false
            }
            else -> {
                store.setAdultsCode(trimmed)
                onSave(trimmed)
                newCode = ""
                message = null
                done = true
            }
        }
    }

    Text("Cambiar código", style = MaterialTheme.typography.titleMedium, color = Color.White)
    OutlinedTextField(
        value = newCode,
        onValueChange = { newCode = it; message = null; done = false },
        singleLine = true,
        // Same focus treatment as the field above: `Done` applies and moves down, and D-pad down
        // releases the field even if the IME doesn't fire `onDone`.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { save(); focusManager.moveFocus(FocusDirection.Down) }),
        modifier = Modifier
            .fillMaxWidth(0.4f)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                    focusManager.moveFocus(FocusDirection.Down)
                    true
                } else {
                    false
                }
            },
    )
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ArkivRed) }
    if (done) {
        Text("Código actualizado", style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
    }
    TvActionOption(label = "Guardar código") { save() }
}
