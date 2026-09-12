package com.arkiv.player.playback

/**
 * A buffer that fills on one end while it's read from the other.
 *
 * Exists so magis's startup stops BLOCKING. Until 2026-08-11, `precalentar` downloaded the whole
 * 2 MB startup chunk before publishing the playlist: measured on the Fire TV, that cost 0.5 to 5s
 * of spinner on every playback, and was the dominant phase of startup.
 *
 * But what libVLC needed to avoid giving up on identifying the stream was never the 2 MB itself:
 * it was that its FIRST READ didn't wait (see [ArchiveCacheProxy.precalentar]). With this, the
 * proxy hands out bytes as they arrive from the origin: the player opens as soon as there's
 * something, and never runs out of data because the buffer keeps growing behind it. It's what any
 * player that "starts right away" does -- start and keep downloading -- instead of fetching a
 * fixed block first.
 *
 * Downloading a fixed block was already tried and isn't the same: trimming it to 512 KB left the
 * player without data mid-identification (see the note on `ARRANQUE_CALIENTE`). Nothing gets
 * trimmed here -- it just stops waiting.
 *
 * Safe for one writer and several readers: [escribir] is called by the thread downloading from
 * the origin, and [porcion]/[esperarHasta] by the threads serving the player.
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
