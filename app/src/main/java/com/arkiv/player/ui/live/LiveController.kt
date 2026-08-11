package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Abre canales en vivo: resuelve contra el gateway y le entrega a VLC la URL del proxy local.
 *
 * Guarda la última sesión resuelta por canal porque **resolver cuesta ~3 s** (dos llamadas al
 * portal, cada una cortada a 1,5 s). Eso es lo que hace que el zapping no se sienta lento:
 * mientras el overlay está quieto, se precalienta por lo bajo el canal siguiente Y el anterior
 * (ver [precalentar]) para que `abrir()` los encuentre ya resueltos.
 *
 * Las dependencias entran como funciones (no como `LiveApi`/`LiveHlsProxy`) para poder probarlo
 * sin red ni sockets; el cableado real vive en `AppGraph`.
 */
class LiveController(
    private val resolver: suspend (String) -> LiveSession,
    private val urlPara: (LiveSession) -> String,
    private val ahora: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    // ConcurrentHashMap y no un mutableMapOf con un Mutex de corrutina alrededor: cerrar() es
    // `fun`, no `suspend` (se puede llamar desde cualquier hilo, p.ej. al salir de la pantalla
    // de vivo), así que no puede tomar el mismo Mutex que abrir()/precalentar() sin volverse
    // suspend o bloquear con runBlocking (con riesgo de deadlock si lo llama el mismo hilo que
    // ya sostiene un candado de abajo). Con un HashMap plano, ese clear() desde un hilo mientras
    // OTRO hilo escribe en abrir()/precalentar() es modificación concurrente sin sincronización
    // compartida -comportamiento indefinido, no solo una escritura perdida-. ConcurrentHashMap
    // deja que get/put/clear convivan sin necesitar el mismo candado en ambos lados.
    //
    // Sobre el tamaño: crece un poco con cada canal distinto que el usuario visita, pero el
    // catálogo de canales en vivo es finito (categorías+canales del portal) y cada LiveSession
    // son un puñado de Strings cortos -no es una fuga real, tiene un techo natural en el tamaño
    // del catálogo, muy lejos de lo que importa en una sesión de zapping por larga que sea.
    private val sesiones = ConcurrentHashMap<String, LiveSession>()

    // Un candado POR CANAL, no uno global envolviendo resolver(): si abrir()/precalentar()
    // tomaran el MISMO Mutex para llamar a resolver() (~3 s de red), precalentar(vecino
    // anterior) y precalentar(vecino siguiente) -disparados como corrutinas separadas mientras
    // el overlay está quieto- se serializarían entre sí sin motivo (~6 s en vez de ~3 s), y un
    // abrir() de un tercer canal quedaría bloqueado detrás de un precalentado ajeno. Eso es
    // justo lo que el precalentado existe para evitar. Con un candado por canal, cada uno solo
    // se serializa contra pedidos CONCURRENTES A SÍ MISMO; canales distintos resuelven en
    // paralelo.
    private val candados = ConcurrentHashMap<String, Mutex>()

    // computeIfAbsent (no el getOrPut de Kotlin): getOrPut no es atómico bajo carrera -dos
    // hilos pueden ver el mapa sin la entrada al mismo tiempo, crear cada uno su propio Mutex y
    // pisarse el put-, y ahí dos corrutinas pidiendo el MISMO canal a la vez terminarían
    // esperando candados DISTINTOS, perdiendo el descarte mutuo que este candado existe para
    // dar. El computeIfAbsent de ConcurrentHashMap sí garantiza una única instancia por clave
    // aunque varios hilos lo llamen a la vez.
    private fun candadoDe(code: String): Mutex = candados.computeIfAbsent(code) { Mutex() }

    private fun vigente(code: String): LiveSession? =
        sesiones[code]?.takeIf { it.expiresAt == 0L || it.expiresAt > ahora() }

    suspend fun abrir(code: String): String {
        val s = vigente(code) ?: candadoDe(code).withLock {
            // Reconfirmar YA con el candado tomado: pudo haberse resuelto (por un
            // precalentar() del MISMO canal corriendo en paralelo) mientras esperábamos.
            vigente(code) ?: resolver(code).also { sesiones[code] = it }
        }
        return urlPara(s)
    }

    /**
     * Best-effort: si [resolver] falla, no propaga la excepción (el canal se resolverá
     * normalmente cuando se llame a [abrir]) ni deja nada cacheado -la asignación a [sesiones]
     * sólo corre si [resolver] devuelve, así que un fallo no dopa la caché con estado parcial.
     */
    suspend fun precalentar(code: String) {
        if (vigente(code) != null) return
        runCatching {
            candadoDe(code).withLock {
                if (vigente(code) == null) sesiones[code] = resolver(code)
            }
        }
    }

    fun cerrar() = sesiones.clear()
}
