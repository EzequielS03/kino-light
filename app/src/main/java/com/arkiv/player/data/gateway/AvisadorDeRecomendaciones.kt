package com.arkiv.player.data.gateway

import kotlinx.coroutines.CancellationException

/**
 * Avisa al gateway que un capítulo pasó a visto, para que reconsidere la fila "Para ti" (spec
 * `2026-08-16-recomendaciones-por-historial`).
 *
 * **Dispara y se olvida**, mismo criterio que
 * [com.arkiv.player.miniaturas.BajadorDeFrames]: guardar que se vio un capítulo es lo importante,
 * este aviso es un extra que se puede perder sin consecuencia. Un fallo de red, un timeout o un
 * 5xx del gateway se traga acá (logueado) y NUNCA sube -- si subiera, [ArkivRepository] tendría
 * que envolver cada sitio donde algo pasa a visto en su propio try/catch, y bastaría con que uno
 * se olvidara para que un gateway caído rompiera el guardado del progreso, que es la parte que de
 * verdad importa.
 *
 * Nunca lanza, salvo [CancellationException] -tragarla dejaría corriendo una corrutina que su
 * scope ya dio por muerta, el mismo motivo por el que [BajadorDeFrames] la vuelve a lanzar.
 */
class AvisadorDeRecomendaciones(private val apiClient: ArkivApiClient) {
    suspend fun avisar() {
        try {
            apiClient.refrescarRecomendaciones()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(
                "ArkivRepo",
                "refrescar recomendaciones: ${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }
}
