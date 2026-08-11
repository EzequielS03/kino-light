package com.arkiv.player.playback

import com.arkiv.player.data.gateway.LiveApi
import com.arkiv.player.data.gateway.LiveSignature
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** De dónde sale el `sign2` de cada petición al CDN. */
interface FirmaDeSegmentos {
    suspend fun firmar(token: String): LiveSignature

    /** El CDN rechazó la última firma entregada. */
    fun rechazada() {}
}

/** Firma en el aparato. Es aritmética local: ni red, ni espera, ni pool. */
class FirmaLocal : FirmaDeSegmentos {
    override suspend fun firmar(token: String): LiveSignature {
        val momento = System.currentTimeMillis()
        return LiveSignature(momento, TweakedMd5.signO3(token, momento))
    }
}

/**
 * Firma en el gateway. Pide de a lotes para no hacer una llamada por segmento; con
 * [lote] = 1 pide una firma por petición, que es el modo seguro mientras no esté
 * verificado que el CDN acepta `start_moment` futuros (Tarea 5, paso 6).
 */
class FirmaDelGateway(
    private val api: LiveApi,
    private val lote: Int = 1,
    private val spreadMs: Long = 0,
) : FirmaDeSegmentos {
    private val cerrojo = Mutex()
    private val pendientes = ArrayDeque<LiveSignature>()

    override suspend fun firmar(token: String): LiveSignature = cerrojo.withLock {
        if (pendientes.isEmpty()) pendientes.addAll(api.firmar(token, lote, spreadMs))
        if (pendientes.size == 1) pendientes.first() else pendientes.removeFirst()
    }

    override fun rechazada() {
        pendientes.clear()  // lo que quedaba en el lote ya no sirve
    }
}

/**
 * Firma en el aparato y, si el CDN rechaza [umbral] firmas **seguidas**, pasa a pedírselas
 * al gateway por lo que resta de la reproducción.
 *
 * El contador se reinicia con cada firma aceptada a propósito: un 403 aislado es una firma
 * que llegó tarde, no un algoritmo roto. Lo que se quiere detectar es el caso en que Magis
 * cambió la firma — ahí fallan todas, y el gateway (que se arregla con un redespliegue,
 * sin publicar APK) toma la posta.
 *
 * La interfaz no tiene un `aceptada()`: la aceptación es implícita — si a una firma emitida
 * NO le sigue un [rechazada] antes del próximo [firmar], fue aceptada. Por eso el reinicio del
 * contador no puede pasar apenas se entrega la firma (ahí todavía no se sabe el veredicto):
 * pasa recién al PRINCIPIO del siguiente [firmar], y solo si la anterior quedó "pendiente" —
 * es decir, nadie la rechazó mientras tanto. [pendiente] es justamente esa bandera.
 */
class FirmaConRespaldo(
    private val local: FirmaDeSegmentos,
    private val remota: FirmaDeSegmentos,
    private val umbral: Int = 2,
) : FirmaDeSegmentos {
    @Volatile var usandoRespaldo: Boolean = false
        private set
    private var rechazosSeguidos = 0
    private var pendiente = false

    override suspend fun firmar(token: String): LiveSignature {
        if (!usandoRespaldo && pendiente) rechazosSeguidos = 0
        val elegida = if (usandoRespaldo) remota else local
        return elegida.firmar(token).also { if (!usandoRespaldo) pendiente = true }
    }

    override fun rechazada() {
        if (usandoRespaldo) { remota.rechazada(); return }
        pendiente = false
        rechazosSeguidos++
        if (rechazosSeguidos >= umbral) usandoRespaldo = true
    }
}
