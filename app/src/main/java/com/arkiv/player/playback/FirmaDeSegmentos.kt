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
 * NO le sigue un [rechazada] antes del próximo [firmar] **del mismo hilo**, fue aceptada.
 *
 * `LiveHlsProxy` abre un hilo real por conexión aceptada (uno para el poll del playlist, uno
 * por cada segmento en vuelo) y todos comparten la MISMA instancia de esta clase — pero cada
 * conexión llama a [firmar] y [rechazada] siempre **desde su propio hilo**, en pares (pide una
 * firma, la usa, y si el CDN la rechaza, avisa antes de reintentar — nunca se mezcla con lo que
 * hace otra conexión). Por eso "pendiente" — si la última firma que pedí sigue sin veredicto —
 * se rastrea **por hilo** con un [ThreadLocal], no con un flag global.
 *
 * Esto no es solo prolijidad: un flag global compartido es un bug real, no únicamente uno de
 * memoria. Con un solo `Boolean`/`Int` compartidos — aunque estén protegidos por un lock, como
 * en un primer intento de este fix — un hilo A pide una firma (marca "pendiente" en la variable
 * GLOBAL) y todavía no tuvo veredicto; antes de que A avise el rechazo, un hilo C totalmente
 * ajeno pide SU PROPIA firma, ve la bandera compartida en `true` (dejada por A) y por eso
 * resetea el contador — borrando de un plumazo rechazos de OTRAS conexiones que ya habían
 * contado. Medido: con 64 hilos rechazando a la vez, el contador nunca llegaba al umbral en
 * 30/30 rondas, CON o SIN el lock — el lock evita que se pisen escrituras, pero no evita que un
 * hilo cancele el conteo de otro. Con el [ThreadLocal], el hilo C ve SU PROPIO "pendiente"
 * (nunca usado todavía, en `false`) y no puede tocar el rastro de A ni de nadie más.
 *
 * `rechazosSeguidos` y `usandoRespaldo` sí son estado realmente compartido entre hilos (el
 * conteo total tiene que ser uno solo), así que esos dos van protegidos por [estado].
 */
class FirmaConRespaldo(
    private val local: FirmaDeSegmentos,
    private val remota: FirmaDeSegmentos,
    private val umbral: Int = 2,
) : FirmaDeSegmentos {
    @Volatile var usandoRespaldo: Boolean = false
        private set
    private var rechazosSeguidos = 0
    private val estado = Any()
    private val pendiente = ThreadLocal.withInitial { false }

    override suspend fun firmar(token: String): LiveSignature {
        // Lectura del volatile SIN el lock: es de solo ida (false→true, nunca vuelve), así que
        // una lectura desactualizada en la ventana de la conmutación cuesta a lo sumo una firma
        // local de más — no un contador que se pierde.
        val enRespaldo = usandoRespaldo
        if (!enRespaldo && pendiente.get() == true) {
            synchronized(estado) { rechazosSeguidos = 0 }
        }
        val elegida = if (enRespaldo) remota else local
        val firma = elegida.firmar(token)
        if (!enRespaldo) pendiente.set(true)
        return firma
    }

    override fun rechazada() {
        if (usandoRespaldo) { remota.rechazada(); return }
        pendiente.set(false)
        synchronized(estado) {
            rechazosSeguidos++
            if (rechazosSeguidos >= umbral) usandoRespaldo = true
        }
    }
}
