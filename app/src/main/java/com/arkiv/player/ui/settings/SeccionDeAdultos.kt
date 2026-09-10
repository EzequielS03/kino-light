package com.arkiv.player.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * El candado 18+ en el celular. Misma sección que la del televisor (`TvSeccionAdultos`), con la
 * misma lógica —[CandadoDeAdultos] y [SettingsStore] son los mismos— y otra capa visual: allá es
 * tv-material3 con foco de D-pad, acá Material3 con teclado en pantalla.
 *
 * Existía el consumidor y faltaba la puerta: `LiveViewModel` ya pedía las categorías con
 * `incluirAdultos` según esta preferencia, y su propio comentario decía que destrabarlo "desde
 * Ajustes" tenía que verse al volver a entrar — pero en el celular no había dónde hacerlo. Se
 * desbloqueaba en el TV o no se desbloqueaba.
 *
 * Es del APARATO, no de la cuenta: el celular y el televisor se destraban por separado y cada uno
 * puede tener su propio código.
 */
@Composable
internal fun SeccionDeAdultos(store: SettingsStore) {
    var desbloqueado by remember { mutableStateOf(store.adultosDesbloqueado.value) }
    var guardado by remember { mutableStateOf(store.codigoAdultos.value) }
    var codigo by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val teclado = LocalSoftwareKeyboardController.current

    if (CandadoDeAdultos.hayQueMostrarLaSeccion(desbloqueado)) {
        Text(
            "Adultos",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        // En el celular la 18+ sale SOLO en En vivo: el cajón de canales, que en el televisor
        // también la muestra, no existe acá.
        Text(
            "La categoría 18+ está visible en En vivo de este aparato.",
            style = MaterialTheme.typography.bodySmall,
            color = ArkivTextSecondary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Button(onClick = {
            store.setAdultosDesbloqueado(false)
            desbloqueado = false
            codigo = ""
        }) {
            Text("Ocultar 18+ en este aparato")
        }
        CambiarCodigoDeAdultos(store) { guardado = it }
        return
    }
    if (!CandadoDeAdultos.hayQueMostrarElCampo(desbloqueado)) return

    fun intentar() {
        // El reseteo se mira ANTES de abrir: es la salida para quien olvidó el código que puso, y
        // por eso no hay ninguna otra pista de que exista. La señal de que funcionó es que el
        // aviso del código por defecto vuelve a aparecer solo.
        if (CandadoDeAdultos.pideReseteo(codigo)) {
            store.setCodigoAdultos(null)
            guardado = null
            codigo = ""
            error = false
            return
        }
        if (CandadoDeAdultos.abre(codigo, CandadoDeAdultos.codigoEfectivo(guardado))) {
            store.setAdultosDesbloqueado(true)
            desbloqueado = true
            error = false
        } else {
            error = true
        }
    }

    // Sin etiquetar como "adultos": el renglón dice "Código" y nada más. Quien no sabe el código
    // no tiene por qué enterarse de que hay una puerta.
    Text(
        "Código",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
    // Mientras el código sea el que sabe cualquiera, se dice. Es lo que hace que la sección sea
    // usable por quien instala el APK sin haberlo compilado; desaparece con un código propio.
    if (CandadoDeAdultos.esElDefault(guardado)) {
        Text(
            "Por defecto: ${CandadoDeAdultos.CODIGO_POR_DEFECTO}",
            style = MaterialTheme.typography.bodySmall,
            color = ArkivTextSecondary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    OutlinedTextField(
        value = codigo,
        onValueChange = { codigo = it; error = false },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { intentar(); teclado?.hide() }),
        modifier = Modifier.fillMaxWidth(),
    )
    if (error) {
        Text("Código incorrecto", style = MaterialTheme.typography.bodySmall, color = ArkivRed)
    }
    Button(onClick = ::intentar, modifier = Modifier.padding(top = 8.dp)) {
        Text("Aplicar código")
    }
}

/**
 * Cambiar el código, solo desde adentro de la sección ya desbloqueada. No pide el código actual:
 * para llegar hasta acá ya hubo que escribirlo.
 *
 * [alGuardar] avisa a [SeccionDeAdultos] cuál quedó, para que su aviso de "por defecto" refleje el
 * cambio sin releer el store.
 */
@Composable
private fun CambiarCodigoDeAdultos(store: SettingsStore, alGuardar: (String) -> Unit) {
    var nuevo by remember { mutableStateOf("") }
    var mensaje by remember { mutableStateOf<String?>(null) }
    var listo by remember { mutableStateOf(false) }
    val teclado = LocalSoftwareKeyboardController.current

    fun guardar() {
        val limpio = nuevo.trim()
        when {
            !CandadoDeAdultos.formatoValido(limpio) -> {
                mensaje = "Usa 4 dígitos"
                listo = false
            }
            // Sin explicar por qué: decir "ese es el de reseteo" sería anunciar la salida que el
            // reseteo existe para no anunciar.
            CandadoDeAdultos.estaReservado(limpio) -> {
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

    Text(
        "Cambiar código",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
    OutlinedTextField(
        value = nuevo,
        onValueChange = { nuevo = it; mensaje = null; listo = false },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { guardar(); teclado?.hide() }),
        modifier = Modifier.fillMaxWidth(),
    )
    mensaje?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ArkivRed) }
    if (listo) {
        Text("Código actualizado", style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
    }
    Button(onClick = ::guardar, modifier = Modifier.padding(top = 8.dp)) {
        Text("Guardar código")
    }
}
