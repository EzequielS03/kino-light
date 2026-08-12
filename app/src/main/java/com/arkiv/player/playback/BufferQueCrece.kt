package com.arkiv.player.playback

/**
 * Un buffer que se llena por un lado mientras se lee por el otro.
 *
 * Existe para que el arranque de magis deje de BLOQUEAR. Hasta el 2026-08-11, `precalentar` bajaba
 * los 2 MB del arranque enteros y recién ahí se publicaba la playlist: medido en el Fire TV, eso
 * costaba entre 0,5 y 5 s de spinner en cada reproducción, y era la fase dominante del arranque.
 *
 * Pero lo que libVLC necesita para no rendirse identificando el stream NO son los 2 MB: es que su
 * PRIMERA LECTURA no espere (ver [ArchiveCacheProxy.precalentar]). Con esto, el proxy le entrega los
 * bytes a medida que llegan del origen: VLC abre apenas hay algo, y nunca se queda sin datos porque
 * el buffer sigue creciendo detrás. Es lo que hace cualquier reproductor que "arranca de una" —
 * empezar y seguir bajando— en vez de descargar un bloque fijo primero.
 *
 * Bajar el bloque fijo ya se probó y no es lo mismo: recortarlo a 512 KB dejó a VLC sin datos a
 * mitad de la identificación (ver la nota de `ARRANQUE_CALIENTE`). Acá no se recorta nada — solo se
 * deja de esperar.
 *
 * Seguro para un escritor y varios lectores: [escribir] lo llama el hilo que baja del origen, y
 * [porcion]/[esperarHasta] los hilos que le sirven a VLC.
 */
class BufferQueCrece(capacidad: Int) {

    private val datos = ByteArray(capacidad)
    private val candado = Object()

    /** Cuántos bytes válidos hay. Volátil: los lectores lo miran sin tomar el candado. */
    @Volatile private var largo = 0

    /** Ya no va a llegar nada más (el origen terminó, cortó o falló). */
    @Volatile var cerrado = false
        private set

    val disponible: Int get() = largo

    /** Agrega [n] bytes de [origen]. Lo que no entra en la capacidad se descarta en silencio. */
    fun escribir(origen: ByteArray, n: Int) {
        synchronized(candado) {
            val cabe = minOf(n, datos.size - largo)
            if (cabe > 0) {
                origen.copyInto(datos, largo, 0, cabe)
                largo += cabe
            }
            // Se avisa SIEMPRE, aunque no haya entrado nada: si el buffer se llenó, el que espera
            // más bytes tiene que despertarse igual para no quedarse colgado hasta el timeout.
            (candado as Object).notifyAll()
        }
    }

    /** No va a haber más datos. Despierta a todos los que estaban esperando. */
    fun cerrar() {
        synchronized(candado) {
            cerrado = true
            (candado as Object).notifyAll()
        }
    }

    /**
     * Espera hasta que haya al menos [n] bytes. Devuelve false si se cerró antes de llegar a esa
     * marca o si se venció [timeoutMs] — en los dos casos el que llama tiene que salir del bucle,
     * no reintentar.
     */
    fun esperarHasta(n: Int, timeoutMs: Long): Boolean {
        val limite = System.currentTimeMillis() + timeoutMs
        synchronized(candado) {
            while (largo < n && !cerrado) {
                val queda = limite - System.currentTimeMillis()
                if (queda <= 0) return false
                (candado as Object).wait(queda)
            }
            return largo >= n
        }
    }

    /** Copia de los bytes que hay desde [desde] hasta lo que haya llegado. Vacío si no hay nada nuevo. */
    fun porcion(desde: Int): ByteArray {
        val hasta = largo
        if (desde >= hasta) return ByteArray(0)
        return datos.copyOfRange(desde, hasta)
    }
}
