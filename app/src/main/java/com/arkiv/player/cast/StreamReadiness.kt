package com.arkiv.player.cast

import java.net.InetSocketAddress
import java.net.Socket

/**
 * ¿Un servidor local ya está ENTREGANDO datos, no solo escuchando?
 *
 * La distinción no es teórica: es la diferencia entre que el Chromecast reproduzca o se vaya a idle.
 * Medido contra el receptor real —a libVLC se le pedía transcodificar y 36 ms después se le decía al
 * receptor que cargara la URL— el puerto todavía no existía, el receptor recibía conexión rechazada
 * y **no reintenta**: se queda en idle para siempre, sin disparar ningún error.
 */
object StreamReadiness {

    /** Abre una conexión y exige al menos un byte. Cierra siempre lo que abre. */
    fun servesData(host: String, port: Int, connectTimeoutMs: Int, readTimeoutMs: Int): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.soTimeout = readTimeoutMs
                // Aceptar la conexión no alcanza: libVLC bindea el puerto antes de tener el primer
                // byte transcodificado, y un stream vacío deja al receptor igual de colgado.
                socket.getOutputStream().write("GET /cast.ts HTTP/1.0\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
                socket.getInputStream().read() != -1
            }
        }.getOrDefault(false)
}
