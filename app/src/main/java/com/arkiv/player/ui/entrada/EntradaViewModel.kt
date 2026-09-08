package com.arkiv.player.ui.entrada

import androidx.lifecycle.ViewModel
import com.arkiv.player.data.gateway.ErrorDeCuenta
import com.arkiv.player.pocketbase.AccountManager
import com.arkiv.player.pocketbase.EstadoDeSesion
import com.arkiv.player.pocketbase.SesionDePersona
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lo que decide `MainActivity`: componer la app o pedir entrada. Ver el KDoc de [EntradaViewModel]. */
sealed interface EstadoDeEntrada {
    /**
     * Sin sesión: hace falta loguearse o registrarse. [aviso] es el mensaje de "no se pudo
     * conectar" que deja [EntradaViewModel.manejarErrorDeCuenta] tras un `backend_no_disponible`
     * -no es un error puntual del formulario (esos los muestra `AnonimoSection` inline), es que no
     * se pudo ni preguntar-.
     */
    data class Entrada(val aviso: String? = null) : EstadoDeEntrada

    /** Hay sesión guardada: se compone la app directo, sin esperar a validarla contra el servidor. */
    data object Adentro : EstadoDeEntrada
}

/**
 * Deriva el estado del gate a partir de [EstadoDeSesion] -la única fuente que "manda" para "puedo
 * entrar", ver la nota de la Task 3 sobre el posible desfase entre [SesionDePersona.estado] y
 * [AccountManager.state]- más el aviso de conectividad que haya quedado pendiente. Función pura y
 * de nivel de archivo a propósito: así se puede probar sin instanciar el ViewModel ni tocar
 * corrutinas.
 */
fun estadoDeEntrada(sesion: EstadoDeSesion, aviso: String?): EstadoDeEntrada = when (sesion) {
    is EstadoDeSesion.Con -> EstadoDeEntrada.Adentro
    EstadoDeSesion.Sin -> EstadoDeEntrada.Entrada(aviso)
}

/**
 * El gate de sesión: `MainActivity` lo consulta antes de armar Room, el sync o las filas del home,
 * igual que ya hace con el gate de integridad (`motivosParaNoArrancar`).
 *
 * A propósito NO valida el token contra el servidor al arrancar: [sesionEstado] es una lectura
 * puramente local de [SesionDePersona.estado], nunca un pedido de red. Bloquear el arranque hasta
 * hablar con un PocketBase que corre en un NUC saturado suma segundos cada vez y no compra nada -la
 * revocación se detecta igual en el primer pedido que la app haga de ahí en más, a lo sumo un
 * minuto después por el TTL de caché del gateway-.
 */
class EntradaViewModel(
    private val sesion: SesionDePersona,
    /** Expuesto para que la pantalla reuse `AnonimoSection` (ui/settings/AccountSection.kt) tal
     *  cual en vez de reimplementar el login acá: si el formulario tiene un bug, se arregla
     *  en un solo lugar. */
    val account: AccountManager,
) : ViewModel() {

    val sesionEstado: StateFlow<EstadoDeSesion> = sesion.estado

    private val _aviso = MutableStateFlow<String?>(null)
    val aviso: StateFlow<String?> = _aviso.asStateFlow()

    /**
     * La regla que ya costó dos rondas de corrección en este proyecto -una del lado del gateway,
     * otra en [SesionDePersona] (Task 1)-, expuesta acá explícitamente para que cualquier lugar que
     * reciba un [ErrorDeCuenta] de la sesión de la persona la reuse en vez de reinventarla mal:
     *
     * - Rechazo de identidad REAL ([ErrorDeCuenta.SesionInvalida], [ErrorDeCuenta.LicenciaNoVigente],
     *   [ErrorDeCuenta.IdentidadInvalida]): la sesión guardada ya no sirve, se cierra y el gate
     *   vuelve a pedir entrada.
     * - Backend caído ([ErrorDeCuenta.BackendNoDisponible]: 503, sin red, timeout): NO es un
     *   rechazo, es que no se pudo ni preguntar. Cerrar la sesión acá dejaría a la persona afuera
     *   Y sin la sesión que tenía, frente a una pantalla de entrada que tampoco funciona sin
     *   backend -el callejón sin salida que describe el spec-.
     *
     * El resto de los códigos (`credenciales_invalidas`, `tope_alcanzado`, ...) son errores
     * puntuales de un intento de login: los maneja `AnonimoSection` inline, no el gate.
     */
    fun manejarErrorDeCuenta(error: ErrorDeCuenta) {
        when (error) {
            is ErrorDeCuenta.SesionInvalida,
            is ErrorDeCuenta.LicenciaNoVigente,
            is ErrorDeCuenta.IdentidadInvalida -> {
                sesion.cerrar()
                _aviso.value = error.mensaje
            }
            is ErrorDeCuenta.BackendNoDisponible -> _aviso.value = error.mensaje
            else -> Unit
        }
    }

    /** Descarta el aviso de conectividad, p.ej. antes de reintentar desde la pantalla de entrada. */
    fun limpiarAviso() { _aviso.value = null }
}
