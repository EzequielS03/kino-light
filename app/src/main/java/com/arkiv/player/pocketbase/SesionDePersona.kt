package com.arkiv.player.pocketbase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Estado de la sesión de la PERSONA (distinto del estado de [AccountManager], que además sabe de Magis). */
sealed interface EstadoDeSesion {
    data object Sin : EstadoDeSesion
    data class Con(val email: String) : EstadoDeSesion
}

/**
 * Sesión de la PERSONA (no la del aparato, que ya resuelve [DeviceAuthManager]): el token que
 * autoriza pedidos al gateway en su nombre. Antes `AccountManager.login()` autenticaba contra
 * PocketBase y tiraba el token apenas lo usaba para adoptar el `accountId` — sin persistirlo no
 * hay forma de hablarle al gateway como esa persona más adelante (otra pantalla, otro arranque de
 * la app), así que esto es la base de la que depende el resto del plan.
 *
 * El token vive en las mismas prefs cifradas que ya usa la identidad del aparato ([DeviceStore] /
 * `SecureDeviceStore`), nunca en storage plano.
 */
class SesionDePersona(
    private val client: PocketBaseClient,
    private val store: DeviceStore,
) {
    private val users = PocketBaseConfig.COLLECTION_USERS

    private val _estado = MutableStateFlow(estadoInicial())
    val estado: StateFlow<EstadoDeSesion> = _estado.asStateFlow()

    /** El token guardado, o null si no hay sesión (para el header `Authorization` de los pedidos). */
    fun token(): String? = store.personToken()

    /** Autentica contra PocketBase y persiste token + email. Si las credenciales no valen o no hay
     *  red, lanza y no toca lo que ya estuviera guardado. */
    suspend fun iniciar(email: String, password: String) {
        val auth = client.authWithPasswordRecord(users, email, password)
        store.savePersonToken(auth.token)
        store.savePersonEmail(email)
        _estado.value = EstadoDeSesion.Con(email)
    }

    /**
     * Refresca el token contra PocketBase (`auth-refresh`). La distinción que importa es entre un
     * RECHAZO de identidad y un fallo de TRANSPORTE:
     * - PocketBase responde 401/403: el token guardado ya no vale (expiró, la cuenta se borró) →
     *   la sesión se cerró de verdad, se limpia.
     * - Cualquier otra cosa (sin red, timeout, 5xx, un [PocketBaseException] con otro código): no
     *   se pudo ni preguntar, así que el token guardado sigue siendo válido. No tocarlo — cerrar la
     *   sesión acá dejaría a la persona sin sesión y frente a una pantalla de login que tampoco
     *   funciona sin backend (el mismo callejón que costó dos rondas de corrección en el gateway).
     *
     * Devuelve `true` si el refresco tuvo éxito, `false` en cualquier otro caso (haya cerrado la
     * sesión o no).
     */
    suspend fun refrescar(): Boolean {
        val actual = store.personToken() ?: return false
        return try {
            val auth = client.authRefresh(users, actual)
            store.savePersonToken(auth.token)
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: PocketBaseException) {
            if (e.code == 401 || e.code == 403) cerrar()
            false
        } catch (e: Exception) {
            // Fallo de transporte (sin red, timeout, host caído): NO es un rechazo de identidad.
            false
        }
    }

    /** Cierra la sesión local: borra token y email. No avisa al servidor (no hace falta: el token
     *  simplemente deja de mandarse). */
    fun cerrar() {
        store.clearPersonToken()
        store.clearPersonEmail()
        _estado.value = EstadoDeSesion.Sin
    }

    /** El token es la fuente de verdad de si hay sesión: un email guardado sin token (instalaciones
     *  previas a este cambio, que solo guardaban el email) no alcanza para hablarle al gateway. */
    private fun estadoInicial(): EstadoDeSesion {
        val token = store.personToken()
        val email = store.personEmail()
        return if (!token.isNullOrBlank() && email != null) EstadoDeSesion.Con(email) else EstadoDeSesion.Sin
    }
}
