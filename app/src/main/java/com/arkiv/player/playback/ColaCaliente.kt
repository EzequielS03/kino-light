package com.arkiv.player.playback

/**
 * El FINAL del archivo, servido desde memoria.
 *
 * Al abrir un TS por HTTP, libVLC sondea el final: pide rangos a pocos KB del EOF para sacar el
 * último PCR y deducir la duración. Esas peticiones son las que se llevaban el arranque — medido el
 * 2026-08-11 en el Fire TV, con el video ya en `Playing` pero clavado en `pos=0`:
 *
 * ```
 * bytes=859421696-   (a 940 B del final)    → 206 con 0 KB
 * bytes=859419628-   (a 3 008 B del final)  → 206 con 2 KB
 * bytes=859415492-   (a 7 144 B del final)  → tres intentos vencidos seguidos
 * ```
 *
 * Y no es que el CDN esté roto ahí: pedidos desde el Mac, esos MISMOS offsets contestaron 24 de 24
 * veces en 0,14-0,37 s. Es la latencia del camino real la que a veces se pasa de cualquier plazo
 * razonable, y ajustar el plazo solo mueve el problema — con uno corto se corta a alguien que iba a
 * contestar, con uno largo se espera a alguien que no.
 *
 * Por eso esto no juega a los dados: la cola se baja UNA vez junto con el arranque (ver
 * [ArchiveCacheProxy.precalentar]) y desde ahí los sondeos de VLC se contestan sin tocar la red.
 * Es la misma idea del arranque caliente del byte 0, aplicada a la otra punta.
 *
 * Es una función pura para poder fijar por test los bordes, que es donde esto sería peligroso:
 * entregar un cuerpo más corto que el `Content-Length` deja al reproductor esperando para siempre,
 * y sin error visible.
 */
object ColaCaliente {

    /**
     * Los bytes de [rango] si caen ENTEROS dentro de la cola guardada, o null para que lo resuelva
     * el origen.
     *
     * [inicioDeLaCola] es el byte absoluto donde arranca [cola]; [total] el tamaño del archivo. Se
     * exige tener las dos cosas y que el rango entre completo: servir de menos sería peor que ir a
     * la red.
     */
    fun servir(inicioDeLaCola: Long, cola: ByteArray, rango: ByteRange?, total: Long): ByteArray? {
        if (cola.isEmpty() || total <= 0L || rango == null) return null
        if (rango.start < inicioDeLaCola) return null
        // El final pedido: `bytes=N-` es "hasta el EOF".
        val fin = rango.end ?: (total - 1)
        if (fin > total - 1 || fin < rango.start) return null
        // Y el tramo pedido tiene que estar cubierto por lo que guardamos.
        val ultimoQueTenemos = inicioDeLaCola + cola.size - 1
        if (fin > ultimoQueTenemos) return null
        val desde = (rango.start - inicioDeLaCola).toInt()
        val hasta = (fin - inicioDeLaCola).toInt() + 1
        return cola.copyOfRange(desde, hasta)
    }
}
