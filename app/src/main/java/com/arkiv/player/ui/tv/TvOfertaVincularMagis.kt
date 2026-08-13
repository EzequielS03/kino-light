package com.arkiv.player.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.pocketbase.AccountException
import com.arkiv.player.pocketbase.AccountManager
import com.arkiv.player.pocketbase.AccountState
import com.arkiv.player.ui.theme.ArkivRed
import kotlinx.coroutines.launch

/**
 * Decide si corresponde ofrecer vincular Magis apenas se entra a la TV (Task 10).
 *
 * Separada de la Composable a propósito -mismo criterio que `entrarDesdeTv` en
 * `TvPantallaDeEntrada.kt`-: este proyecto no tiene infraestructura de tests de UI de Compose, así
 * que la única forma de probar la condición ("¿aparece la pantalla o no?") es que viva en una
 * función pura, aparte.
 *
 * Se ofrece únicamente cuando:
 * - la cuenta de Kino YA está conectada ([AccountState.Conectado]: sin sesión no hay nada que
 *   vincular, y este gate solo se compone dentro de `ArkivTvRoot`, que ya exige sesión);
 * - todavía NO tiene Magis vinculado ([AccountState.Conectado.magisLinked]);
 * - la persona no dijo "Ahora no" antes en este aparato ([descartada], persistido en
 *   `SettingsStore.magisOfertaDescartada` -ver su KDoc sobre qué lo resetea-).
 */
fun debeOfrecerVincularMagis(state: AccountState, descartada: Boolean): Boolean =
    state is AccountState.Conectado && !state.magisLinked && !descartada

/** Qué campo recibe las teclas del teclado en pantalla de [TvOfertaVincularMagis]. */
private enum class CampoMagisOferta { EMAIL, PASSWORD }

/**
 * Se ofrece como lo PRIMERO al entrar a la TV cuando la cuenta de Kino todavía no tiene Magis
 * vinculado (Task 10, ver [debeOfrecerVincularMagis] para la condición exacta).
 *
 * ### Por qué vive acá y no en `TvPantallaDeEntrada`
 *
 * `MainActivity` recompone a `ArkivTvRoot` en cuanto `SesionDePersona.estado` pasa a tener sesión
 * (ver su gate, `estadoDeEntrada`) -la pantalla de entrada deja de existir en ese instante. Si esta
 * pantalla fuera un paso más de esa, nunca llegaría a mostrarse: para cuando hay algo que vincular
 * (una cuenta de Kino ya conectada) ya se está del otro lado del gate. Por eso se compone como lo
 * primero DENTRO de `ArkivTvRoot`, y así sirve para las dos rutas de entrada -login en la propia TV
 * (Task 9) y pareo desde el celular (Task 5)-, no solo para una.
 *
 * ### Por qué solo vincular, no registrar
 *
 * Solo cubre [AccountManager.vincularMagis] (una cuenta de Magis que YA EXISTE). El registro de una
 * cuenta nueva ([AccountManager.vincularMagisEnviarCodigo]/[AccountManager.vincularMagisConfirmar])
 * manda un código por email que hay que ir a leer -no es un flujo para hacer con un control remoto-.
 * Ese camino sigue disponible en Ajustes (`TvVincularMagisSection`), para quien sí puede leer ese
 * email en el momento.
 *
 * ### Por qué un fallo acá nunca cierra la sesión de Kino
 *
 * Magis es el proveedor de identidad "real" (créditos/plan); Kino es la cuenta de esta app. Son dos
 * identidades DISTINTAS (ver el KDoc de `AccountManager`), y que Magis rechace unas credenciales no
 * dice nada sobre si la cuenta de Kino sigue viva. [AccountManager.vincularMagis] ya está escrito
 * así -atrapa `AccountException` y la muestra, sin tocar `sesion` ni deslogear a nadie-; acá se repite
 * el mismo cuidado en el manejo del error (solo `error = e.message`) porque en este proyecto ya se
 * confundieron cosas parecidas ("te revocaron" con "el servidor no contesta") tres veces, y cada vez
 * dejó a alguien afuera sin motivo.
 *
 * Reusa [TvTecladoYCampos]/[CampoTvChip]/[TvBotonMostrarPassword] de `PanelDeLogin` -no los copia-,
 * ver el KDoc de `TvFormularioConTeclado.kt`.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvOfertaVincularMagis(account: AccountManager, accountEmail: String, onAhoraNo: () -> Unit) {
    val scope = rememberCoroutineScope()
    val campos = rememberTvCamposConFoco(CampoMagisOferta.EMAIL)
    // Precargado con el email de la cuenta de Kino: en general es el mismo que el de Magis, y
    // ahorra tipearlo -mismo criterio que TvVincularMagisSection en Ajustes-. Solo una vez (Unit):
    // si la persona lo borra o lo cambia, no se lo pisamos en la próxima recomposición.
    LaunchedEffect(Unit) { campos.escribir(CampoMagisOferta.EMAIL, accountEmail) }
    var passwordVisible by remember { mutableStateOf(false) }
    var modoTeclado by remember { mutableStateOf(TvKeyboardMode.MINUS) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun vincular() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                account.vincularMagis(campos.valor(CampoMagisOferta.EMAIL).trim(), campos.valor(CampoMagisOferta.PASSWORD))
                // No hace falta "cerrar" nada acá: en cuanto vincularMagis deja magisLinked = true,
                // debeOfrecerVincularMagis da false y quien llama (ArkivTvRoot) deja de componer esta
                // pantalla solo, por la recomposición normal de account.state.
            } catch (e: AccountException) {
                // SOLO el mensaje de Magis. Nunca account.logout() ni tocar sesion: un rechazo de
                // Magis es un problema de Magis, no de la cuenta de Kino (ver KDoc de arriba).
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    val puedeVincular = !busy &&
        campos.valor(CampoMagisOferta.EMAIL).isNotBlank() &&
        campos.valor(CampoMagisOferta.PASSWORD).isNotBlank()

    TvTecladoYCampos(
        titulo = "Vincular tu cuenta de Magis",
        subtitulo = "Opcional: da acceso a tu plan de Magis desde Kino. \"Ahora no\" para saltear -queda disponible en Ajustes.",
        modoTeclado = modoTeclado,
        onModo = { modoTeclado = it },
        textoActivo = campos.valorActivo(),
        onTextoActivoChange = { campos.escribirEnActivo(it); error = null },
        // Mismo motivo que en PanelDeLogin: `@`/`.` a la vista sin cambiar de capa mientras se
        // tipea el email.
        extras = if (campos.activo == CampoMagisOferta.EMAIL) listOf('@', '.') else emptyList(),
    ) { focoPrimerCampo ->
        CampoTvChip(
            etiqueta = "Email de Magis",
            valor = campos.valor(CampoMagisOferta.EMAIL),
            activo = campos.activo == CampoMagisOferta.EMAIL,
            onFocus = { campos.enfocar(CampoMagisOferta.EMAIL) },
            modifier = Modifier.focusRequester(focoPrimerCampo),
        )
        CampoTvChip(
            etiqueta = "Contraseña de Magis",
            valor = if (passwordVisible) {
                campos.valor(CampoMagisOferta.PASSWORD)
            } else {
                "•".repeat(campos.valor(CampoMagisOferta.PASSWORD).length)
            },
            activo = campos.activo == CampoMagisOferta.PASSWORD,
            // Enmascarado SOLO mientras está oculta -ver el comentario de PanelDeLogin sobre por qué
            // (PasswordVisualTransformation enmascara el dibujo, no la semántica de accesibilidad)-.
            enmascarado = !passwordVisible,
            onFocus = { campos.enfocar(CampoMagisOferta.PASSWORD) },
        )
        TvBotonMostrarPassword(visible = passwordVisible, onToggle = { passwordVisible = !passwordVisible })

        error?.let {
            Text(
                it,
                color = ArkivRed,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
            Surface(
                onClick = { vincular() },
                enabled = puedeVincular,
                modifier = Modifier.height(48.dp),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                colors = arkivTvSurfaceColors(),
                border = arkivTvSurfaceBorder(),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (busy) "Vinculando…" else "Vincular",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                }
            }
            Surface(
                // "Ahora no" no depende de `busy`: si Magis está tardando en responder, la persona
                // tiene que poder salir igual -no es un botón destructivo, así que no hay riesgo de
                // dejar algo a medias.
                onClick = onAhoraNo,
                modifier = Modifier.height(48.dp),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                colors = arkivTvSurfaceColors(),
                border = arkivTvSurfaceBorder(),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Ahora no",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                }
            }
        }
    }
}
