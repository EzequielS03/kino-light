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

/** Lo que es de la app en este televisor: actualizaciones y el candado 18+. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvSettingsApp() {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var manualUpdate by remember { mutableStateOf<UpdateInfo?>(null) }

    // Chequeo manual: independiente del diálogo global de MainActivity, así funciona aunque
    // este último ya haya sido descartado por el usuario en esta sesión.
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

    TvSeccionAdultos(graph.settings)
}

/**
 * El candado de la sección 18+.
 *
 * Sin destrabar se ve UN renglón que pide un código, y nada más: ni el nombre de la sección, ni
 * un botón en gris, ni un candado. Anunciar que existe algo es la mitad del problema — quien no
 * sabe el código no tiene por qué enterarse de que hay una puerta.
 *
 * Destraba SOLO este aparato ([SettingsStore.setAdultosDesbloqueado] va a los ajustes del aparato,
 * no a la cuenta): el televisor del living no hereda lo que se destrabó en el celular, y
 * desinstalar la app lo apaga.
 *
 * Lo que hace al destrabarse es que la app pida las categorías con `incluirAdultos = true`; el
 * propio cliente (`MagisLiveCatalog`) las filtra por defecto cuando no. O sea que `18+` aparece
 * como una categoría más en la guía de En vivo y en el cajón de canales, que es exactamente donde
 * el portal la pone.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvSeccionAdultos(store: SettingsStore) {
    var desbloqueado by remember { mutableStateOf(store.adultosDesbloqueado.value) }
    var guardado by remember { mutableStateOf(store.codigoAdultos.value) }
    var codigo by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    if (AdultsLock.shouldShowSection(desbloqueado)) {
        Text("Adultos", style = MaterialTheme.typography.titleMedium, color = Color.White)
        Text(
            "La categoría 18+ está visible en En vivo y en el cajón de canales de este aparato.",
            style = MaterialTheme.typography.bodySmall,
            color = ArkivTextSecondary,
        )
        TvActionOption(label = "Ocultar 18+ en este aparato") {
            store.setAdultosDesbloqueado(false)
            desbloqueado = false
            codigo = ""
        }
        TvCambiarCodigoDeAdultos(store) { guardado = it }
        return
    }
    if (!AdultsLock.shouldShowField(desbloqueado)) return

    val focusManager = LocalFocusManager.current

    fun intentar() {
        // El reseteo se mira ANTES de abrir: es la salida para quien olvidó el código que puso, y
        // por eso no hay ninguna otra pista de que exista. La señal de que funcionó es que el
        // aviso del código por defecto vuelve a aparecer solo.
        if (AdultsLock.requestsReset(codigo)) {
            store.setCodigoAdultos(null)
            guardado = null
            codigo = ""
            error = false
            return
        }
        if (AdultsLock.unlocks(codigo, AdultsLock.effectiveCode(guardado))) {
            store.setAdultosDesbloqueado(true)
            desbloqueado = true
            error = false
        } else {
            error = true
        }
    }

    // Sin etiquetar como "adultos": el renglón dice "Código" y nada más.
    Text("Código", style = MaterialTheme.typography.titleMedium, color = Color.White)
    // Mientras el código sea el que sabe cualquiera, se dice. Es lo que hace que la sección sea
    // usable por quien instala el APK sin haberlo compilado; desaparece con un código propio.
    if (AdultsLock.isDefault(guardado)) {
        Text(
            "Por defecto: ${AdultsLock.DEFAULT_CODE}",
            style = MaterialTheme.typography.bodySmall,
            color = ArkivTextSecondary,
        )
    }
    OutlinedTextField(
        value = codigo,
        onValueChange = { codigo = it; error = false },
        singleLine = true,
        // `Done` que APLICA, no que solo cierra el teclado. En un televisor, cerrar el IME deja el
        // foco atrapado en el campo -- el D-pad no lo suelta y no se llega al botón de abajo. Con
        // esto el código se aplica sin tener que salir del campo, que es el camino natural: se
        // termina de escribir y se confirma en el mismo teclado.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { intentar(); focusManager.moveFocus(FocusDirection.Down) }),
        // Y la salida de emergencia: abajo sale del campo aunque el IME no coopere. Sin esto, un
        // teclado que se cierra sin disparar `onDone` deja el foco encerrado y no hay forma de
        // llegar al botón con el control.
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
    TvActionOption(label = "Aplicar código") { intentar() }
}

/**
 * Cambiar el código, solo desde adentro de la sección ya desbloqueada. No pide el código actual:
 * para llegar hasta acá ya hubo que escribirlo.
 *
 * [alGuardar] avisa a [TvSeccionAdultos] cuál quedó, para que su aviso de "por defecto" refleje
 * el cambio sin releer el store.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvCambiarCodigoDeAdultos(store: SettingsStore, alGuardar: (String) -> Unit) {
    var nuevo by remember { mutableStateOf("") }
    var mensaje by remember { mutableStateOf<String?>(null) }
    var listo by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    fun guardar() {
        val limpio = nuevo.trim()
        when {
            !AdultsLock.isValidFormat(limpio) -> {
                mensaje = "Usa 4 dígitos"
                listo = false
            }
            // Sin explicar por qué: decir "ese es el de reseteo" sería anunciar la salida que el
            // reseteo existe para no anunciar.
            AdultsLock.isReserved(limpio) -> {
                mensaje = "Ese código no está disponible, elige otro"
                listo = false
            }
            else -> {
                store.setCodigoAdultos(limpio)
                alGuardar(limpio)
                nuevo = ""
                mensaje = null
                listo = true
            }
        }
    }

    Text("Cambiar código", style = MaterialTheme.typography.titleMedium, color = Color.White)
    OutlinedTextField(
        value = nuevo,
        onValueChange = { nuevo = it; mensaje = null; listo = false },
        singleLine = true,
        // Mismo trato de foco que el campo de arriba: `Done` aplica y baja, y el D-pad hacia abajo
        // suelta el campo aunque el IME no dispare `onDone`.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { guardar(); focusManager.moveFocus(FocusDirection.Down) }),
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
    mensaje?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ArkivRed) }
    if (listo) {
        Text("Código actualizado", style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
    }
    TvActionOption(label = "Guardar código") { guardar() }
}
