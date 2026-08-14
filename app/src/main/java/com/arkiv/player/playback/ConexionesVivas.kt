package com.arkiv.player.playback

import java.util.Collections

/**
 * Las conexiones al origen que están abiertas AHORA, para poder abandonarlas todas de golpe.
 *
 * Existe por [CambioDeRed]: cuando el aparato cambia de red, los sockets abiertos quedaron atados a
 * una interfaz que ya no existe, y sin nadie que los cierre la lectura se queda esperando hasta el
 * plazo del cuerpo de [PoliticaOrigen] —90 s en archive, 30 s en magis— antes de que empiece
 * siquiera el primer reintento. Cerrarlos hace que esa lectura falle en el acto y que el reintento
 * salga por la red nueva.
 *
 * NO es [ConexionUnica], que hacía algo distinto y se abandonó: aquella cerraba la conexión anterior
 * del MISMO origen, creyendo que el CDN atendía de a una, y terminó siendo la causa del fallo que
 * decía evitar (le cortaba a libVLC las lecturas con las que identifica el stream, dejándolo en
 * `pistas=v0/a0`). Acá abrir una conexión no cierra ninguna otra: el único momento en que se cierra
 * algo es cuando alguien de afuera dice que ya no sirven.
 *
 * Seguro entre hilos: registra el thread que atiende cada petición y cierra el que recibe el aviso
 * de red.
 */
class ConexionesVivas {

    private val abiertas: MutableSet<ConexionUnica.Cerrable> =
        Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /** Suma [conexion] a las vivas. No toca a ninguna otra. */
    fun registrar(conexion: ConexionUnica.Cerrable) {
        abiertas.add(conexion)
    }

    /** Da de baja [conexion] porque terminó sola. Después de esto, [cerrarTodas] ya no la toca. */
    fun soltar(conexion: ConexionUnica.Cerrable) {
        abiertas.remove(conexion)
    }

    /**
     * Cierra todo lo que haya abierto y vacía el registro. Devuelve cuántas cerró, para el log.
     *
     * Cada cierre va envuelto: un socket ya muerto puede tirar al cerrarse, y eso es lo esperable
     * —no puede impedir que se cierren las demás, que es justo lo que hay que hacer.
     */
    fun cerrarTodas(): Int {
        var cerradas = 0
        val iterador = abiertas.iterator()
        while (iterador.hasNext()) {
            val c = iterador.next()
            iterador.remove()
            cerradas++
            runCatching { c.cerrar() }
        }
        return cerradas
    }
}
