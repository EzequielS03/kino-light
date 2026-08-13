package com.arkiv.player.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkiv.player.data.gateway.Aparato
import com.arkiv.player.data.gateway.CuentaApi
import com.arkiv.player.data.gateway.ErrorDeCuenta
import com.arkiv.player.pocketbase.SesionDePersona
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Un [Aparato] tal como se pinta en "Mis aparatos": [esEsteAparato] ya está resuelto (Task 6:
 * comparando el `id` contra el `recordId` LOCAL de este aparato, sin preguntarle al gateway -ver
 * el brief-), así que ni el ViewModel ni la UI necesitan repetir esa comparación en cada lugar.
 */
data class AparatoUi(
    val id: String,
    val kind: String,
    val nombre: String,
    val ultimoUso: String,
    val esEsteAparato: Boolean,
)

/** Lo que pinta la pantalla de "Mis aparatos". */
sealed interface EstadoMisAparatos {
    data object Cargando : EstadoMisAparatos
    data class Cargado(val aparatos: List<AparatoUi>) : EstadoMisAparatos

    /** El LISTADO falló ([CuentaApi.listarAparatos]). Separado del aviso de "sacar"
     *  ([MisAparatosViewModel.avisoError]): son dos pedidos distintos y uno no debe tapar al otro. */
    data class Error(val mensaje: String) : EstadoMisAparatos
}

/** Etiqueta legible de [Aparato.kind] ("phone"/"tv"). Cualquier otro valor cae en un genérico -el
 *  gateway puede sumar tipos nuevos sin que esta pantalla se rompa, mismo espíritu que
 *  [ErrorDeCuenta.Desconocido]-. */
fun etiquetaDeTipo(kind: String): String = when (kind) {
    "phone" -> "Celular"
    "tv" -> "TV"
    else -> "Aparato"
}

/** Nombre a mostrar: el que vino del gateway, o un genérico por tipo si vino vacío -el brief es
 *  explícito: `nombre` puede venir vacío o nulo (el `CuentaApi` ya lo normaliza a `""`), y la
 *  pantalla tiene que tolerarlo sin romperse-. */
fun nombreParaMostrar(aparato: AparatoUi): String = aparato.nombre.ifBlank { etiquetaDeTipo(aparato.kind) }

private val FORMATO_ULTIMO_USO: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(java.time.ZoneId.systemDefault())

/**
 * "Visto por última vez" ya formateado en hora local, o `""` si el gateway no lo sabe (aparato
 * recién dado de alta) o mandó algo que no es un instante ISO-8601 válido -ninguno de los dos casos
 * debe romper el render, mismo motivo que [nombreParaMostrar]-.
 */
fun ultimoUsoParaMostrar(ultimoUso: String): String {
    if (ultimoUso.isBlank()) return ""
    val instante = runCatching { java.time.Instant.parse(ultimoUso) }.getOrNull() ?: return ""
    return FORMATO_ULTIMO_USO.format(instante)
}

/**
 * Mensaje de confirmación para sacar [aparato] de la cuenta.
 *
 * Distinto según sea o no ESTE aparato -el punto central de la Task 6: desde la Task 5b sacar un
 * aparato lo desconecta de verdad en <=60s, así que sacar el propio te cierra la sesión ACÁ MISMO.
 * Una confirmación genérica ("¿sacar este aparato?") es una trampa acá -el brief lo dice con esas
 * palabras-, así que esta función es la única fuente de ese texto: se prueba sola, sin Compose,
 * igual que [com.arkiv.player.ui.entrada.estadoDeEntrada].
 *
 * [esElUltimo] evita que la copia del último aparato suene a "perdiste la cuenta": no es un estado
 * roto, se puede volver a entrar y el aparato se da de alta de nuevo.
 */
fun mensajeDeConfirmacion(aparato: AparatoUi, esElUltimo: Boolean): String = when {
    aparato.esEsteAparato && esElUltimo ->
        "Vas a sacar este aparato -el único que tenés dado de alta-. Se cierra la sesión ACÁ MISMO " +
            "(a lo sumo en 60 segundos) y vas a tener que volver a entrar. Podés hacerlo cuando " +
            "quieras: el aparato se da de alta de nuevo solo."
    aparato.esEsteAparato ->
        "Vas a sacar este aparato de tu cuenta. Se cierra la sesión ACÁ MISMO (a lo sumo en 60 " +
            "segundos) y vas a tener que volver a entrar."
    else ->
        "\"${nombreParaMostrar(aparato)}\" va a perder el acceso a tu cuenta en menos de un minuto."
}

/**
 * Estado y lógica de "Mis aparatos" (Task 6): lista los aparatos de la cuenta, marca cuál es ESTE
 * -comparando localmente el `id` contra [recordIdDeEsteAparato], sin preguntarle al gateway- y saca
 * uno con confirmación explícita en dos pasos ([pedirSacar] / [confirmarSacar]).
 *
 * Domain puro, NO `androidx.lifecycle.ViewModel`: expone `suspend fun` y quien compone la pantalla
 * las lanza con `rememberCoroutineScope()`, igual que ya hace [AccountSection] con
 * [com.arkiv.player.pocketbase.AccountManager]. Mismo motivo en los dos casos: se puede probar con
 * `runBlocking` sin depender de `Dispatchers.Main`, y una sola instancia (graph-level, ver
 * `AppGraph.misAparatosViewModel`) se comparte entre la pantalla del celular y la de la TV.
 *
 * [recordIdDeEsteAparato] es un provider, no un valor capturado, por la misma razón que
 * `AppGraph.arkivApiClient` lee `{ deviceAuth.session.value?.token }` en vez de un valor: al
 * construirse esta clase el bootstrap del device puede no haber terminado todavía.
 */
class MisAparatosViewModel(
    private val cuentaApi: CuentaApi,
    private val sesion: SesionDePersona,
    private val recordIdDeEsteAparato: () -> String?,
) {
    private val _estado = MutableStateFlow<EstadoMisAparatos>(EstadoMisAparatos.Cargando)
    val estado: StateFlow<EstadoMisAparatos> = _estado.asStateFlow()

    /**
     * Aparato pedido para sacar, pendiente de confirmación. Mientras esto no sea `null`,
     * [confirmarSacar] TODAVÍA NO llamó al gateway -es la garantía que pide el brief: "sacar pide
     * confirmación: sin confirmar, no sale ninguna llamada HTTP"-.
     */
    private val _pendienteDeSacar = MutableStateFlow<AparatoUi?>(null)
    val pendienteDeSacar: StateFlow<AparatoUi?> = _pendienteDeSacar.asStateFlow()

    /**
     * Id del aparato que se está sacando AHORA (el DELETE está en vuelo), o `null` si ninguno.
     * No alcanza con un booleano: [pendienteDeSacar] ya se limpió antes de que esto se ponga en
     * `true` (ver [confirmarSacar]), así que sin el id la UI no podría saber CUÁL fila marcar como
     * "Sacando…" -sí puede, en cambio, usarlo para deshabilitar el diálogo entero-.
     */
    private val _sacandoId = MutableStateFlow<String?>(null)
    val sacandoId: StateFlow<String?> = _sacandoId.asStateFlow()

    /** Aviso de que "sacar" falló. Separado de [EstadoMisAparatos.Error] (que es del LISTADO):
     *  son dos pedidos distintos, y un fallo de uno no debe tapar el resultado del otro. */
    private val _avisoError = MutableStateFlow<String?>(null)
    val avisoError: StateFlow<String?> = _avisoError.asStateFlow()

    suspend fun cargar() {
        _estado.value = EstadoMisAparatos.Cargando
        try {
            val lista = cuentaApi.listarAparatos().map { it.aUi() }
            _estado.value = EstadoMisAparatos.Cargado(lista)
        } catch (e: ErrorDeCuenta) {
            manejarErrorDeSesion(e)
            _estado.value = EstadoMisAparatos.Error(e.mensaje)
        }
    }

    private fun Aparato.aUi() = AparatoUi(id, kind, nombre, ultimoUso, id == recordIdDeEsteAparato())

    /** Paso 1: pide confirmación. Todavía NO toca la red -ver [pendienteDeSacar]-. */
    fun pedirSacar(aparato: AparatoUi) {
        _pendienteDeSacar.value = aparato
    }

    fun cancelarSacar() {
        _pendienteDeSacar.value = null
    }

    /** Paso 2: la persona ya confirmó. Acá sí sale el DELETE. */
    suspend fun confirmarSacar() {
        val aparato = _pendienteDeSacar.value ?: return
        _pendienteDeSacar.value = null
        _sacandoId.value = aparato.id
        try {
            cuentaApi.sacarAparato(aparato.id)
            if (aparato.esEsteAparato) {
                // Es de verdad (Task 5b): el gateway ya no va a validar el token de ESTE aparato
                // en el próximo pedido. Cerrar acá mismo, sin esperar al primer 401 -la persona ya
                // fue avisada en la confirmación, así que esto no debería sorprenderla-.
                sesion.cerrar()
            } else {
                cargar()
            }
        } catch (e: ErrorDeCuenta.AparatoNoEncontrado) {
            // Ya no existe (otro aparato lo sacó primero, un doble toque): lo que la persona quería
            // -que no esté- ya se cumplió. No es un error real: refrescar y seguir (brief).
            cargar()
        } catch (e: ErrorDeCuenta) {
            manejarErrorDeSesion(e)
            _avisoError.value = e.mensaje
        } finally {
            _sacandoId.value = null
        }
    }

    /**
     * Misma regla que [com.arkiv.player.ui.entrada.EntradaViewModel.manejarErrorDeCuenta]: solo un
     * rechazo de identidad REAL cierra la sesión. Un `backend_no_disponible` (503/sin red/timeout)
     * NO -es la falla que ya costó tres rondas de corrección en este proyecto (ver el brief)-.
     */
    private fun manejarErrorDeSesion(e: ErrorDeCuenta) {
        when (e) {
            is ErrorDeCuenta.SesionInvalida,
            is ErrorDeCuenta.LicenciaNoVigente,
            is ErrorDeCuenta.IdentidadInvalida -> sesion.cerrar()
            else -> Unit
        }
    }

    fun limpiarAvisoError() {
        _avisoError.value = null
    }
}

// ---------------------------------------------------------------------------------------------
// Composables (celular). El equivalente de TV vive en TvSettingsScreen.kt -misma instancia de
// [MisAparatosViewModel] (AppGraph.misAparatosViewModel), otra UI- igual que AccountSection/
// TvAccountSection comparten un solo AccountManager.
// ---------------------------------------------------------------------------------------------

@Composable
fun MisAparatosSection(vm: MisAparatosViewModel) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    val pendiente by vm.pendienteDeSacar.collectAsStateWithLifecycle()
    val sacandoId by vm.sacandoId.collectAsStateWithLifecycle()
    val avisoError by vm.avisoError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.cargar() }

    Text(
        "Mis aparatos",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )

    when (val e = estado) {
        EstadoMisAparatos.Cargando -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
        is EstadoMisAparatos.Error -> {
            Text(e.mensaje, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { scope.launch { vm.cargar() } }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Reintentar")
            }
        }
        is EstadoMisAparatos.Cargado -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                e.aparatos.forEach { aparato ->
                    AparatoRow(
                        aparato = aparato,
                        sacando = sacandoId == aparato.id,
                        onSacar = { vm.pedirSacar(aparato) },
                    )
                }
            }
            avisoError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }

            pendiente?.let { aparato ->
                val esElUltimo = e.aparatos.size <= 1
                val ocupado = sacandoId != null
                AlertDialog(
                    onDismissRequest = vm::cancelarSacar,
                    title = { Text("Sacar \"${nombreParaMostrar(aparato)}\"") },
                    text = { Text(mensajeDeConfirmacion(aparato, esElUltimo)) },
                    confirmButton = {
                        TextButton(
                            enabled = !ocupado,
                            onClick = { scope.launch { vm.confirmarSacar() } },
                        ) { Text(if (ocupado) "Sacando…" else "Sacar") }
                    },
                    dismissButton = {
                        TextButton(enabled = !ocupado, onClick = vm::cancelarSacar) { Text("Cancelar") }
                    },
                )
            }
        }
    }
}

@Composable
private fun AparatoRow(aparato: AparatoUi, sacando: Boolean, onSacar: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                nombreParaMostrar(aparato) + if (aparato.esEsteAparato) "  ·  este aparato" else "",
                style = MaterialTheme.typography.bodyLarge,
                color = if (aparato.esEsteAparato) ArkivRed else MaterialTheme.colorScheme.onBackground,
            )
            val detalle = etiquetaDeTipo(aparato.kind) +
                ultimoUsoParaMostrar(aparato.ultimoUso).let { if (it.isNotBlank()) " · visto $it" else "" }
            Text(detalle, style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
        }
        Button(enabled = !sacando, onClick = onSacar) { Text(if (sacando) "Sacando…" else "Sacar") }
    }
}
