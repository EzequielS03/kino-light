package com.arkiv.player.data.magis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Mensaje listo para mostrar: distingue "la clave está mal" de "Magis no contesta". */
class MagisException(mensaje: String) : RuntimeException(mensaje)

sealed interface EstadoDeMagis {
    data object Sin : EstadoDeMagis
    data class Vinculada(val email: String) : EstadoDeMagis
}

/**
 * El vínculo con Magis visto desde la UI. Reemplaza lo único que quedaba vivo de
 * `AccountManager` en las tres pantallas que lo consumían (`AccountSection`, `TvSettingsCuenta`,
 * `TvOfertaVincularMagis`): sostener si hay cuenta y avisar cuando cambia. `AccountManager` sigue
 * existiendo -lo usan `PantallaDeEntrada`/`TvPantallaDeEntrada` para el login de Kino- pero ya no
 * tiene llamador desde esas tres pantallas.
 */
internal class CuentaDeMagis(private val session: MagisSession) {
    private val _estado = MutableStateFlow<EstadoDeMagis>(EstadoDeMagis.Sin)
    val estado: StateFlow<EstadoDeMagis> = _estado.asStateFlow()

    /**
     * Se llama una vez al entrar a la pantalla, NO en el constructor: leer el email sale de
     * `EncryptedSharedPreferences` (disco + descifrado) y este objeto se construye desde `AppGraph`,
     * que se toca desde el hilo principal. En el KALLEY eso son milisegundos que se notan.
     */
    suspend fun refrescar() = withContext(Dispatchers.IO) {
        _estado.value = session.emailVinculado()?.let { EstadoDeMagis.Vinculada(it) } ?: EstadoDeMagis.Sin
    }

    suspend fun vincular(email: String, clave: String) {
        when (session.login(email, clave)) {
            is MagisResult.Ok -> _estado.value = EstadoDeMagis.Vinculada(email)
            is MagisResult.RedError -> throw MagisException("Magis no disponible")
            // El portal dice POR QUÉ en chino: se muestra el nuestro y el suyo queda en el log.
            is MagisResult.PortalError -> throw MagisException("Credenciales de Magis inválidas")
        }
    }

    suspend fun desvincular() {
        session.logout()
        _estado.value = EstadoDeMagis.Sin
    }
}
