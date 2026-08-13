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
import com.arkiv.player.ui.theme.ArkivTextSecondary
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

/** Qué campo recibe las teclas del teclado en pantalla de [TvOfertaVincularMagis]. EMAIL y
 *  PASSWORD sirven en las dos ramas (vincular una cuenta existente, o ponerle la contraseña a una
 *  nueva) porque nunca se muestran juntas con otro sentido -son mutuamente excluyentes en la
 *  pantalla, ver [TvOfertaVincularMagis]-; CODIGO es solo del paso 2 de crear cuenta (Task 11). */
private enum class CampoMagisOferta { EMAIL, PASSWORD, CODIGO }

/** Los dos pasos de crear una cuenta de Magis NUEVA desde la TV (Task 11): primero el email -pide
 *  el código-, después el código + la contraseña que va a tener la cuenta nueva -los confirma-. */
enum class PasoRegistroMagis { EMAIL, CODIGO }

/**
 * Estado y transiciones del alta de Magis en dos pasos, separado de la Composable -mismo motivo
 * que [TvCamposConFoco] en `TvFormularioConTeclado.kt`: es la única forma de probar "el paso 2 SOLO
 * se alcanza si el envío del código salió bien" y "se puede volver del código al email" sin
 * infraestructura de tests de Compose (este proyecto no la tiene, ver el KDoc de `entrarDesdeTv` en
 * `TvPantallaDeEntrada.kt`)-.
 *
 * No depende de [AccountManager] directo sino de las dos funciones que necesita
 * ([enviarCodigoAMagis]/[confirmarEnMagis]): así un test arma un fake liviano -una lambda que tira
 * o no- en vez de tener que levantar `MockWebServer` -como pide construir un `AccountManager` real,
 * ver `AccountManagerRegistroTest`- solo para probar una transición de estado que no toca red. De
 * yapa, esta clase ni siquiera TIENE acceso a `SesionDePersona`/`logout()`: estructuralmente no
 * existe forma de que un error acá termine tocando la sesión de Kino.
 */
class RegistroMagisFlow(
    private val enviarCodigoAMagis: suspend (email: String) -> Unit,
    private val confirmarEnMagis: suspend (email: String, password: String, code: String) -> Unit,
) {
    var paso: PasoRegistroMagis by mutableStateOf(PasoRegistroMagis.EMAIL)
        private set

    /** Pide el código; [paso] SOLO avanza a CODIGO si Magis lo aceptó. Si [enviarCodigoAMagis]
     *  lanza (email ya registrado, portal caído), la excepción sube TAL CUAL -quien llama la
     *  atrapa y la muestra, ver el KDoc de [TvOfertaVincularMagis]- y [paso] queda en EMAIL: seguir
     *  esperando un código que Magis nunca mandó es exactamente lo que hay que evitar. */
    suspend fun enviarCodigo(email: String) {
        enviarCodigoAMagis(email)
        paso = PasoRegistroMagis.CODIGO
    }

    /** Vuelve del paso del código al del email -para cuando el email se tipeó mal: quedarse
     *  encerrado esperando un código que nunca va a llegar es la peor salida posible-. */
    fun volverAEmail() {
        paso = PasoRegistroMagis.EMAIL
    }

    /** Confirma el código + la contraseña nueva. Si [confirmarEnMagis] lanza (código incorrecto,
     *  portal caído), la excepción sube tal cual y [paso] queda en CODIGO -tiene sentido: el
     *  código en sí puede seguir siendo válido, lo que falló fue otra cosa (o se reenvía si no)-. */
    suspend fun confirmar(email: String, password: String, code: String) =
        confirmarEnMagis(email, password, code)
}

/** `remember` de [RegistroMagisFlow] atado a [account] -mismo patrón que [rememberTvCamposConFoco]
 *  en `TvFormularioConTeclado.kt`-. */
@Composable
fun rememberRegistroMagisFlow(account: AccountManager): RegistroMagisFlow =
    remember { RegistroMagisFlow(account::vincularMagisEnviarCodigo, account::vincularMagisConfirmar) }

/**
 * Se ofrece como lo PRIMERO al entrar a la TV cuando la cuenta de Kino todavía no tiene Magis
 * vinculado (Task 10, ver [debeOfrecerVincularMagis] para la condición exacta). Desde la Task 11
 * también deja CREAR una cuenta de Magis nueva, no solo vincular una que ya existe.
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
 * ### Por qué ahora también se puede crear cuenta (Task 11)
 *
 * Al principio esta pantalla solo cubría [AccountManager.vincularMagis] (una cuenta que YA EXISTE):
 * el registro de una nueva manda un código de verificación por email, y con un control remoto
 * parecía impracticable -quedó afuera a propósito, disponible solo en Ajustes
 * (`TvVincularMagisSection`), para quien sí podía leer ese email en el momento-.
 *
 * Pero el código se LEE en el teléfono y se TIPEA acá -el TV no necesita recibir nada- y es corto:
 * Magis lo manda numérico (confirmado leyendo el gateway, `registro_confirmar` en
 * `arkiv_api/adapters/magis/session.py` y el sentinela de test "000000" en
 * `test_magis_session.py`; el celu ya le pide `KeyboardType.Number` a este mismo campo en
 * `AccountSection`/`TvSettingsScreen`), así que el teclado en pantalla lo puede mostrar en una capa
 * de solo dígitos ([TvKeyboardMode.NUMERICO]) en vez de mandar a la persona a buscarlo entre 26
 * letras con un D-pad.
 *
 * El alta es un flujo de DOS pasos -email primero, código + contraseña nueva después- manejado por
 * [RegistroMagisFlow] (ver su KDoc para el detalle de las transiciones): el paso 2 SOLO se alcanza
 * si Magis aceptó el email, se puede volver al paso 1 en cualquier momento -clave si el email se
 * tipeó mal: quedarse esperando un código que nunca va a llegar es la peor salida posible-, y se
 * puede reenviar el código sin volver al paso 1 -un código que no llega es el caso normal, no el
 * excepcional-.
 *
 * ### Por qué un fallo acá nunca cierra la sesión de Kino
 *
 * Magis es el proveedor de identidad "real" (créditos/plan); Kino es la cuenta de esta app. Son dos
 * identidades DISTINTAS (ver el KDoc de `AccountManager`), y que Magis rechace algo -credenciales al
 * vincular, un email ya registrado al pedir el código, un código incorrecto al confirmar, el portal
 * caído en cualquiera de los tres- no dice nada sobre si la cuenta de Kino sigue viva.
 * [AccountManager.vincularMagis]/[AccountManager.vincularMagisEnviarCodigo]/
 * [AccountManager.vincularMagisConfirmar] ya están escritos así -atrapan `AccountException` y la
 * relanzan, sin tocar `sesion` ni deslogear a nadie-; acá se repite el mismo cuidado en el manejo
 * del error (solo `error = e.message`, y [RegistroMagisFlow] ni siquiera TIENE cómo tocar la sesión
 * de Kino por accidente, ver su KDoc) porque en este proyecto ya se confundieron cosas parecidas
 * ("te revocaron" con "el servidor no contesta") tres veces, y cada vez dejó a alguien afuera sin
 * motivo.
 *
 * Reusa [TvTecladoYCampos]/[CampoTvChip]/[TvBotonMostrarPassword] de `PanelDeLogin` -no los copia-,
 * ver el KDoc de `TvFormularioConTeclado.kt`.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvOfertaVincularMagis(account: AccountManager, accountEmail: String, onAhoraNo: () -> Unit) {
    val scope = rememberCoroutineScope()
    val campos = rememberTvCamposConFoco(CampoMagisOferta.EMAIL)
    val registro = rememberRegistroMagisFlow(account)
    // Precargado con el email de la cuenta de Kino: en general es el mismo que el de Magis, y
    // ahorra tipearlo -mismo criterio que TvVincularMagisSection en Ajustes-. Solo una vez (Unit):
    // si la persona lo borra o lo cambia, no se lo pisamos en la próxima recomposición.
    LaunchedEffect(Unit) { campos.escribir(CampoMagisOferta.EMAIL, accountEmail) }
    var passwordVisible by remember { mutableStateOf(false) }
    var modoTeclado by remember { mutableStateOf(TvKeyboardMode.MINUS) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // "Vincular" (una cuenta de Magis que ya existe) o "Crear cuenta" (Task 11, dos pasos vía
    // [registro]): son dos flujos distintos de Magis, mismo criterio que `registrando` en
    // `PanelDeLogin` para Kino -nunca se muestran juntos, uno u otro-.
    var creandoCuenta by remember { mutableStateOf(false) }

    // El campo activo del teclado sigue al paso del registro: al llegar al paso del código, el
    // foco LÓGICO -no el visual del D-pad, mismo límite ya aceptado en PanelDeLogin con la
    // licencia (ver su comentario)- pasa ahí; al volver al del email, vuelve al email. Sin esto el
    // teclado seguiría escribiendo en un campo que ya no está en pantalla.
    LaunchedEffect(registro.paso) {
        campos.enfocar(
            if (registro.paso == PasoRegistroMagis.CODIGO) CampoMagisOferta.CODIGO else CampoMagisOferta.EMAIL,
        )
    }

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

    /** Paso 1 del alta: pide el código. Sirve tanto para "Enviar código" (primera vez, paso EMAIL)
     *  como para "Reenviar código" (paso CODIGO): el gateway mintea un device fresco por cada
     *  pedido (`registro_enviar_codigo` en `arkiv-api`), así que el código anterior deja de servir
     *  -se limpia el campo para que no reenvíen por error uno que ya no vale-. */
    fun pedirCodigo() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                registro.enviarCodigo(campos.valor(CampoMagisOferta.EMAIL).trim())
                campos.escribir(CampoMagisOferta.CODIGO, "")
            } catch (e: AccountException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    /** Paso 2: confirma el código + la contraseña nueva. */
    fun confirmarRegistro() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                registro.confirmar(
                    campos.valor(CampoMagisOferta.EMAIL).trim(),
                    campos.valor(CampoMagisOferta.PASSWORD),
                    campos.valor(CampoMagisOferta.CODIGO).trim(),
                )
                // Igual que vincular(): en cuanto confirmar() deja magisLinked = true, esta pantalla
                // deja de componerse sola. Nada que cerrar acá.
            } catch (e: AccountException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    /** Del paso del código de vuelta al del email -para el caso del email mal tipeado: quedarse
     *  encerrado esperando un código que nunca va a llegar es la peor salida posible-. No depende
     *  de `busy` por el mismo motivo que "Ahora no": si Magis está tardando, la persona tiene que
     *  poder volver igual. */
    fun volverAEmail() {
        registro.volverAEmail()
        campos.escribir(CampoMagisOferta.CODIGO, "")
        error = null
    }

    val puedeVincular = !busy &&
        campos.valor(CampoMagisOferta.EMAIL).isNotBlank() &&
        campos.valor(CampoMagisOferta.PASSWORD).isNotBlank()
    val puedePedirCodigo = !busy && campos.valor(CampoMagisOferta.EMAIL).isNotBlank()
    val puedeConfirmarRegistro = !busy &&
        campos.valor(CampoMagisOferta.CODIGO).isNotBlank() &&
        campos.valor(CampoMagisOferta.PASSWORD).isNotBlank()

    // La capa NUMERICO (ver su KDoc en TvKeyboard.kt) se fuerza SOLO mientras el campo activo es
    // el del código; el email y las dos contraseñas (la de vincular, la nueva de crear cuenta)
    // siguen usando la variante que la persona haya elegido, igual que siempre.
    val modoEfectivo = if (
        creandoCuenta && registro.paso == PasoRegistroMagis.CODIGO && campos.activo == CampoMagisOferta.CODIGO
    ) {
        TvKeyboardMode.NUMERICO
    } else {
        modoTeclado
    }

    TvTecladoYCampos(
        titulo = when {
            !creandoCuenta -> "Vincular tu cuenta de Magis"
            registro.paso == PasoRegistroMagis.EMAIL -> "Crear tu cuenta de Magis"
            else -> "Confirmá el código"
        },
        subtitulo = when {
            !creandoCuenta -> "Opcional: da acceso a tu plan de Magis desde Kino. \"Ahora no\" para saltear -queda disponible en Ajustes."
            registro.paso == PasoRegistroMagis.EMAIL -> "Te vamos a mandar un código a este email para crear la cuenta."
            else -> "Mirá el código en tu teléfono y tipealo acá, junto con la contraseña que querés para esta cuenta nueva."
        },
        modoTeclado = modoEfectivo,
        onModo = { modoTeclado = it },
        textoActivo = campos.valorActivo(),
        onTextoActivoChange = { campos.escribirEnActivo(it); error = null },
        // Mismo motivo que en PanelDeLogin: `@`/`.` a la vista sin cambiar de capa mientras se
        // tipea el email.
        extras = if (campos.activo == CampoMagisOferta.EMAIL) listOf('@', '.') else emptyList(),
    ) { focoPrimerCampo ->
        // El email solo se ve/edita mientras no se llegó al paso del código -ahí ya está fijo, se
        // muestra como texto (más abajo) y para corregirlo hay que "Volver"-.
        if (!creandoCuenta || registro.paso == PasoRegistroMagis.EMAIL) {
            CampoTvChip(
                etiqueta = "Email de Magis",
                valor = campos.valor(CampoMagisOferta.EMAIL),
                activo = campos.activo == CampoMagisOferta.EMAIL,
                onFocus = { campos.enfocar(CampoMagisOferta.EMAIL) },
                modifier = Modifier.focusRequester(focoPrimerCampo),
            )
        }

        if (!creandoCuenta) {
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
        } else if (registro.paso == PasoRegistroMagis.CODIGO) {
            Text(
                "Código enviado a ${campos.valor(CampoMagisOferta.EMAIL)}",
                style = MaterialTheme.typography.bodySmall,
                color = ArkivTextSecondary,
            )
            CampoTvChip(
                etiqueta = "Código",
                valor = campos.valor(CampoMagisOferta.CODIGO),
                activo = campos.activo == CampoMagisOferta.CODIGO,
                onFocus = { campos.enfocar(CampoMagisOferta.CODIGO) },
            )
            CampoTvChip(
                etiqueta = "Contraseña nueva de Magis",
                valor = if (passwordVisible) {
                    campos.valor(CampoMagisOferta.PASSWORD)
                } else {
                    "•".repeat(campos.valor(CampoMagisOferta.PASSWORD).length)
                },
                activo = campos.activo == CampoMagisOferta.PASSWORD,
                enmascarado = !passwordVisible,
                onFocus = { campos.enfocar(CampoMagisOferta.PASSWORD) },
            )
            TvBotonMostrarPassword(visible = passwordVisible, onToggle = { passwordVisible = !passwordVisible })
        }

        error?.let {
            Text(
                it,
                color = ArkivRed,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
            when {
                !creandoCuenta -> {
                    TvOfertaAccion(if (busy) "Vinculando…" else "Vincular", enabled = puedeVincular, onClick = ::vincular)
                    // Se limpia la contraseña al cambiar de flujo -"Crear cuenta"/"Ya tengo
                    // cuenta" abajo hacen lo mismo-: es un campo distinto en cada rama (la de una
                    // cuenta existente vs. la nueva que se está por crear) y dejar el valor viejo
                    // pre-cargado sin que la persona lo haya tipeado ahí es más confuso que útil.
                    TvOfertaAccion(
                        "Crear cuenta",
                        enabled = !busy,
                        onClick = { creandoCuenta = true; error = null; campos.escribir(CampoMagisOferta.PASSWORD, "") },
                    )
                }
                registro.paso == PasoRegistroMagis.EMAIL -> {
                    TvOfertaAccion(if (busy) "Enviando…" else "Enviar código", enabled = puedePedirCodigo, onClick = ::pedirCodigo)
                    TvOfertaAccion(
                        "Ya tengo cuenta",
                        enabled = !busy,
                        onClick = { creandoCuenta = false; error = null; campos.escribir(CampoMagisOferta.PASSWORD, "") },
                    )
                }
                else -> {
                    TvOfertaAccion(if (busy) "Confirmando…" else "Confirmar", enabled = puedeConfirmarRegistro, onClick = ::confirmarRegistro)
                    TvOfertaAccion(if (busy) "…" else "Reenviar código", enabled = !busy, onClick = ::pedirCodigo)
                    TvOfertaAccion("Volver", enabled = true, onClick = ::volverAEmail)
                }
            }
            // "Ahora no" no depende de `busy`: si Magis está tardando en responder, la persona
            // tiene que poder salir igual -no es un botón destructivo, así que no hay riesgo de
            // dejar algo a medias-. Siempre visible, en cualquier paso: es la salida de toda la
            // pantalla, no solo del paso en el que se está.
            TvOfertaAccion("Ahora no", enabled = true, onClick = onAhoraNo)
        }
    }
}

/**
 * Botón de acción de esta pantalla -Vincular/Crear cuenta/Enviar código/Confirmar/Reenviar
 * código/Volver/Ahora no-, todos con el mismo aspecto. Antes de la Task 11 eran dos `Surface`
 * escritos a mano; con seis más el copy-paste se volvía el problema real -el mismo motivo que ya
 * justificó extraer `TvFormularioConTeclado.kt`, acá a escala de un solo archivo-.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvOfertaAccion(texto: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(48.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = arkivTvSurfaceColors(),
        border = arkivTvSurfaceBorder(),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(texto, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 18.dp))
        }
    }
}
