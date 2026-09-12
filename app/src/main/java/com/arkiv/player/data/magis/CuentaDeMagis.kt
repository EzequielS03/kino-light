package com.arkiv.player.data.magis

import android.util.Log
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
 * `TvOfertaVincularMagis`): sostener si hay cuenta y avisar cuando cambia. `AccountManager`
 * -junto con `PantallaDeEntrada`/`TvPantallaDeEntrada`, que lo usaban para el login de Kino- se
 * borró entero en la Task 9 (sub-proyecto 2B).
 */
internal class CuentaDeMagis(private val session: MagisSession) {
    private val _estado = MutableStateFlow<EstadoDeMagis>(EstadoDeMagis.Sin)
    val estado: StateFlow<EstadoDeMagis> = _estado.asStateFlow()

    /**
     * Se llama una vez al entrar a la pantalla, NO en el constructor: leer el email sale de
     * `EncryptedSharedPreferences` (disco + descifrado) y este objeto se construye desde `AppGraph`,
     * que se toca desde el hilo principal. En el KALLEY eso son milisegundos que se notan.
     *
     * `runCatching` a propósito: la llaman tres `LaunchedEffect` (incluido el de `ArkivTvRoot` en
     * el arranque), y una excepción leyendo disco no puede tumbar la app ahí. Con él, degrada a
     * [EstadoDeMagis.Sin] como si no hubiera cuenta vinculada.
     *
     * Cubre la LECTURA, no la apertura del store: `EncryptedMagisCredentialStore` se construye
     * antes, al evaluar `graph.cuentaDeMagis` (`AppGraph.magisStore`, `by lazy`), y ahí quien
     * protege es `PrefsCifradas.abrirOReparar`, que sí maneja el Keystore roto sin tirar.
     */
    suspend fun refrescar() = withContext(Dispatchers.IO) {
        val email = runCatching { session.emailVinculado() }
            .onFailure { Log.w(TAG, "couldn't read the linked account: assuming not linked", it) }
            .getOrNull()
        _estado.value = email?.let { EstadoDeMagis.Vinculada(it) } ?: EstadoDeMagis.Sin
    }

    suspend fun vincular(email: String, clave: String) = withContext(Dispatchers.IO) {
        when (val r = session.login(email, clave)) {
            is MagisResult.Ok -> _estado.value = EstadoDeMagis.Vinculada(email)
            is MagisResult.RedError -> throw MagisException("Magis no disponible")
            // The portal says WHY, but in Chinese: we show ours and theirs (code +
            // message) goes to the log -- without the code, a "credenciales inválidas" that's
            // actually "aaa100082: this device is already bound to another account" is undiagnosable.
            is MagisResult.PortalError -> {
                Log.w(TAG, "link rejected by the portal: code=${r.codigo}, message=${r.msg}")
                throw MagisException("Credenciales de Magis inválidas")
            }
        }
    }

    suspend fun desvincular() = withContext(Dispatchers.IO) {
        session.logout()
        _estado.value = EstadoDeMagis.Sin
    }

    private companion object {
        const val TAG = "CuentaDeMagis"
    }
}
