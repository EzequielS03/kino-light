package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import com.arkiv.player.data.magis.CuentaDeMagis
import com.arkiv.player.data.magis.EstadoDeMagis
import com.arkiv.player.data.magis.MagisException
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.launch

/**
 * Decide si corresponde ofrecer vincular Magis apenas se entra a la TV (Task 10; desde Task 8,
 * sub-proyecto 2B, ya no mira ninguna sesión de Kino, solo [EstadoDeMagis]).
 *
 * Separada de la Composable a propósito -mismo criterio que `entrarDesdeTv` en
 * `TvPantallaDeEntrada.kt`-: este proyecto no tiene infraestructura de tests de UI de Compose, así
 * que la única forma de probar la condición ("¿aparece la pantalla o no?") es que viva en una
 * función pura, aparte.
 *
 * Se ofrece únicamente cuando:
 * - este aparato todavía NO tiene Magis vinculado ([EstadoDeMagis.Sin]) -no depende de si hay o no
 *   una cuenta de Kino conectada: `MainActivity` compone `ArkivTvRoot` sin gate de sesión (ver su
 *   comentario "Sin gate de sesión"), así que esa condición ya no aplica-;
 * - la persona no dijo "Ahora no" antes en este aparato ([descartada], persistido en
 *   `SettingsStore.magisOfertaDescartada` -ver su KDoc sobre qué lo resetea-).
 */
fun debeOfrecerVincularMagis(estado: EstadoDeMagis, descartada: Boolean): Boolean =
    estado is EstadoDeMagis.Sin && !descartada

/** Qué campo recibe las teclas del teclado en pantalla de [TvOfertaVincularMagis]. */
private enum class CampoMagisOferta { EMAIL, PASSWORD }

/**
 * Se ofrece como lo PRIMERO al entrar a la TV cuando este aparato todavía no tiene Magis vinculado
 * (Task 10, ver [debeOfrecerVincularMagis] para la condición exacta -desde Task 8, sub-proyecto 2B,
 * ya no depende de ninguna cuenta de Kino-). Desde la Task 11 también deja CREAR una cuenta de
 * Magis nueva, no solo vincular una que ya existe.
 *
 * ### Por qué vive acá y no en `TvPantallaDeEntrada`
 *
 * `MainActivity` compone `ArkivTvRoot` sin gate de sesión de Kino (ver su comentario "Sin gate de
 * sesión"): `TvPantallaDeEntrada` sigue en el árbol pero ya no tiene llamador desde ahí. Esta oferta
 * se compone como lo primero DENTRO de `ArkivTvRoot` porque ese es el único lugar por el que pasan
 * las dos rutas que dejan un aparato sin Magis vinculado -uno recién instalado y uno al que se
 * desvinculó-, sin que importe si hay o no una cuenta de Kino de por medio.
 *
 * ### Por qué ya no se puede crear una cuenta de Magis desde acá
 *
 * La Task 11 había sumado el alta de una cuenta nueva en dos pasos (email → código por email →
 * contraseña). El código de verificación lo manda Magis por email y el ida y vuelta lo orquestaba el
 * gateway (`registro_enviar_codigo`/`registro_confirmar`): sin servidor propio no hay quién acuñe el
 * device temporal ni guarde el estado entre los dos pasos, así que el alta se resignó (sub-proyecto
 * 2A). Acá se vincula una cuenta que YA existe, que es lo que esta pantalla hacía al principio.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TvOfertaVincularMagis(cuenta: CuentaDeMagis, onAhoraNo: () -> Unit) {
    // ATRAS SALE DE ESTA PANTALLA, no de la app. `ArkivTvRoot` compone esta oferta y hace `return`
    // antes de llegar a su propio BackHandler, asi que mientras se muestra no habia NINGUNO puesto
    // y el back se lo llevaba el sistema: cerraba Kino entero. Para quien no queria vincular Magis,
    // la unica forma de seguir era salir de la app y volver a entrar.
    //
    // Hace lo mismo que "Ahora no" a proposito: son la misma intencion -- "esto no, ahora"-- y que
    // el boton y el control remoto hagan cosas distintas seria peor que cualquiera de las dos.
    BackHandler(onBack = onAhoraNo)
    val scope = rememberCoroutineScope()
    val campos = rememberTvCamposConFoco(CampoMagisOferta.EMAIL)
    // Ya NO se precarga con ningún email: antes salía de la cuenta de Kino conectada, y desde
    // Task 8 (sub-proyecto 2B) esta pantalla no depende de ninguna. Arranca en blanco.
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
                cuenta.vincular(campos.valor(CampoMagisOferta.EMAIL).trim(), campos.valor(CampoMagisOferta.PASSWORD))
                // No hace falta "cerrar" nada acá: en cuanto vincular deja el estado en Vinculada,
                // debeOfrecerVincularMagis da false y quien llama (ArkivTvRoot) deja de componer esta
                // pantalla solo, por la recomposición normal de cuenta.estado.
            } catch (e: MagisException) {
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
        subtitulo = "Tiene que ser una cuenta que ya exista. Da acceso a tu plan de Magis desde " +
            "Kino, y hace falta para el canal en vivo. \"Ahora no\" para saltear -queda en Ajustes.",
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

        // FlowRow y no Row: en un `Row` que desborda, Compose RECORTA al ultimo hijo -- y el ultimo
        // es justo "Ahora no", la salida de la pantalla. Con la TV en 960 dp esta columna queda en
        // ~382 dp y los tres botones de la rama de vincular suman ~378: al filo. En el paso del
        // codigo son CUATRO y se pasa seguro, asi que la unica salida quedaba fuera de pantalla y
        // Magis parecia obligatorio. Ya habia pasado antes en esta misma columna: ver el comentario
        // de `PESO_CAMPOS` en TvFormularioConTeclado.kt, donde un `width(520.dp)` "se llevaba puesto
        // el boton de crear cuenta". Envolviendo, el ancho deja de poder esconder una salida.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            TvOfertaAccion(if (busy) "Vinculando…" else "Vincular", enabled = puedeVincular, onClick = ::vincular)
            // "Ahora no" no depende de `busy`: si Magis está tardando en responder, la persona
            // tiene que poder salir igual -no es un botón destructivo, así que no hay riesgo de
            // dejar algo a medias-.
            TvOfertaAccion("Ahora no", enabled = true, onClick = onAhoraNo)
        }
    }
}

/** Botón de acción de esta pantalla (Vincular / Ahora no), los dos con el mismo aspecto. */
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
