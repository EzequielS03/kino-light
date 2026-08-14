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

    /** El CDN aceptó la última firma entregada (`pedirAlOrigen` la usó y NO le siguió un 403). */
    fun aceptada() {}
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
 *
 * [pendientes] es un `ArrayDeque` corriente -no thread-safe-, pero lo tocan dos llamantes que NO
 * comparten el mismo candado: [firmar] es `suspend` y usa [cerrojoRed] (un `Mutex` de corrutina),
 * mientras que [rechazada] es `fun` corriente -la llama `LiveHlsProxy` desde el hilo plano de cada
 * conexión, nunca desde una corrutina- y no puede tomar ESE mismo `Mutex` sin volverse `suspend`.
 * Por eso el propio `ArrayDeque` -no la operación de red- se protege con `synchronized(pendientes)`,
 * que ambos SÍ pueden compartir: ninguno de los dos lo sostiene mientras suspende (la llamada a
 * [api] queda siempre FUERA del bloque `synchronized`).
 */
class FirmaDelGateway(
    private val api: LiveApi,
    private val lote: Int = 1,
    private val spreadMs: Long = 0,
) : FirmaDeSegmentos {
    private val cerrojoRed = Mutex()
    private val pendientes = ArrayDeque<LiveSignature>()

    override suspend fun firmar(token: String): LiveSignature {
        synchronized(pendientes) { pendientes.removeFirstOrNull() }?.let { return it }
        // La cola estaba vacía: pedimos al gateway. El Mutex serializa este tramo entre llamadas
        // concurrentes a firmar() para no disparar N pedidos de red redundantes cuando N
        // conexiones se quedan sin firma al mismo tiempo -algo que SÍ puede pasar (un hilo por
        // conexión en LiveHlsProxy).
        return cerrojoRed.withLock {
            // Reconfirmar con el candado de red tomado: otra llamada pudo habernos ganado de mano
            // mientras esperábamos, y dejar la cola con lo que a nosotros nos alcanza.
            synchronized(pendientes) { pendientes.removeFirstOrNull() }?.let { return@withLock it }
            val traidas = api.firmar(token, lote, spreadMs)
            synchronized(pendientes) {
                pendientes.addAll(traidas)
                pendientes.removeFirst()
            }
        }
    }

    override fun rechazada() {
        synchronized(pendientes) { pendientes.clear() }  // lo que quedaba en el lote ya no sirve
    }
}

/**
 * Firma en el aparato y, si el CDN rechaza [umbral] firmas **seguidas**, pasa a pedírselas
 * al gateway por lo que resta de la reproducción.
 *
 * El contador se reinicia con cada firma aceptada: un 403 aislado es una firma que llegó tarde,
 * no un algoritmo roto. Lo que se quiere detectar es el caso en que Magis cambió la firma — ahí
 * fallan todas, y el gateway (que se arregla con un redespliegue, sin publicar APK) toma la posta.
 *
 * La aceptación/rechazo llegan como señales EXPLÍCITAS ([aceptada]/[rechazada]) desde quien de
 * verdad sabe si el CDN aceptó la firma -`LiveHlsProxy.pedirAlOrigen`-, no como algo que esta
 * clase infiere. Esto reemplaza un diseño anterior que trataba de inferir "fue aceptada" con el
 * SILENCIO del llamante (si a una firma emitida no le seguía un [rechazada] antes del próximo
 * [firmar] del MISMO hilo, se asumía aceptada, rastreado con un `ThreadLocal`). Ese diseño tenía
 * un defecto de fondo: `LiveHlsProxy` abre un hilo REAL por conexión aceptada (uno por el poll del
 * playlist, uno por cada segmento en vuelo), así que cada conexión pide UNA firma y su hilo muere
 * -el `ThreadLocal` se iba con él-. La única segunda llamada a `firmar()` dentro del MISMO hilo era
 * el reintento interno de `pedirAlOrigen`, que ocurre DESPUÉS de que `rechazada()` ya había hecho
 * su trabajo: el camino de reinicio nunca se alcanzaba en producción, así que dos 403 cualesquiera
 * en toda la sesión conmutaban al gateway para siempre (hallazgo F1 de la revisión final).
 *
 * Con señales explícitas el problema desaparece de raíz: no hace falta saber en qué hilo corrió
 * cada firma, porque ya no se infiere nada -el propio `pedirAlOrigen` avisa `aceptada()` apenas ve
 * un código de respuesta que no es 403, sin importar en qué conexión/hilo haya sido-.
 *
 * `rechazosSeguidos` y `usandoRespaldo` sí son estado realmente compartido entre hilos (el
 * conteo total tiene que ser uno solo), así que esos dos van protegidos por [estado].
 */
/**
 * Si esta respuesta del CDN significa "no te autorizo", o sea que la firma no sirvio.
 *
 * Existe porque este CDN rechaza con **401**, no con 403, y el codigo solo miraba el 403. Medido en
 * el Google TV el 2026-08-14: ningun canal cargaba mientras el gateway resolvia perfecto
 * (`live resolve OK ... direcciones=2`, 200 en todos). El log del aparato lo destapo:
 *
 * ```
 * 12:56:58.653  playlist → 401 en 346ms
 * 12:56:58.654  502 al reproductor: playlist con codigo 401
 * ```
 *
 * Con un 401, `pedirAlOrigen` llamaba a `aceptada()` -- daba la firma por BUENA-- el contador de
 * rechazos seguidos se reseteaba y [FirmaConRespaldo] no conmutaba nunca al firmador del gateway.
 * Tampoco se llegaba a dar la sesion por muerta, que es el otro camino de recuperacion: las dos
 * defensas estaban mirando el codigo equivocado y el canal moria en un 502 sin remedio.
 *
 * Solo 401 y 403. Un 5xx o un timeout NO son rechazo de firma -- son el CDN teniendo un problema--
 * y contarlos haria conmutar al respaldo por cualquier bache de red.
 */
fun esRechazoDeFirma(codigo: Int): Boolean = codigo == 401 || codigo == 403

class FirmaConRespaldo(
    private val local: FirmaDeSegmentos,
    private val remota: FirmaDeSegmentos,
    private val umbral: Int = 2,
) : FirmaDeSegmentos {
    @Volatile var usandoRespaldo: Boolean = false
        private set
    private var rechazosSeguidos = 0
    private val estado = Any()

    override suspend fun firmar(token: String): LiveSignature {
        // Lectura del volatile SIN el lock: es de solo ida (false→true, nunca vuelve), así que
        // una lectura desactualizada en la ventana de la conmutación cuesta a lo sumo una firma
        // local de más — no un contador que se pierde.
        val elegida = if (usandoRespaldo) remota else local
        return elegida.firmar(token)
    }

    override fun rechazada() {
        if (usandoRespaldo) { remota.rechazada(); return }
        synchronized(estado) {
            rechazosSeguidos++
            if (rechazosSeguidos >= umbral) usandoRespaldo = true
        }
    }

    override fun aceptada() {
        if (usandoRespaldo) { remota.aceptada(); return }
        synchronized(estado) { rechazosSeguidos = 0 }
    }
}

/**
 * Respeta el interruptor de Ajustes ("Forzar servidor", [forzarRemoto]) en CADA llamada, no solo
 * al construirse -así cambiarlo en caliente (reproducir → Ajustes → cambiar → reproducir) tiene
 * efecto en el próximo segmento, sin reiniciar la app. Antes de este envoltorio, `AppGraph`
 * armaba `liveHlsProxy` como `by lazy` leyendo `settings.liveSignRemote.value` UNA sola vez: el
 * interruptor -pensado justo para poder comprobar de vez en cuando que el camino de respaldo
 * sigue andando, sin esperar a que el algoritmo local se rompa de verdad- quedaba mudo hasta
 * matar la app (hallazgo de la revisión final, "en la misma ola").
 *
 * [conRespaldo] es SIEMPRE la misma instancia esté o no forzado el remoto: así su contador de
 * rechazos seguidos no se pierde al alternar el interruptor de un lado a otro.
 */
class FirmaSegunAjustes(
    private val conRespaldo: FirmaConRespaldo,
    private val remota: FirmaDeSegmentos,
    private val forzarRemoto: () -> Boolean,
) : FirmaDeSegmentos {
    private fun elegida(): FirmaDeSegmentos = if (forzarRemoto()) remota else conRespaldo

    override suspend fun firmar(token: String): LiveSignature = elegida().firmar(token)
    override fun rechazada() = elegida().rechazada()
    override fun aceptada() = elegida().aceptada()
}
