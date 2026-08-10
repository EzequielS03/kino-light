package com.arkiv.player.playback

import java.util.concurrent.ConcurrentHashMap

/**
 * Una sola conexión viva por origen.
 *
 * El CDN de magis atiende de a una: cuando se adelanta, el reproductor abre el tramo nuevo antes de
 * que se entere de que el anterior murió, y el CDN deja al nuevo colgado hasta que salta el timeout
 * (en device se veía la petición entrar al proxy y no salir nunca, con el player en "buffering 0%"
 * para siempre). Registrar la nueva cierra la anterior en el acto.
 */
class ConexionUnica {

    /** Lo que se puede cerrar: la conexión HTTP al origen. Interfaz para poder testear sin red. */
    fun interface Cerrable {
        fun cerrar()
    }

    private val activas = ConcurrentHashMap<String, Cerrable>()

    /** Deja [conexion] como la única viva de [origen], cerrando la que hubiera. */
    fun registrar(origen: String, conexion: Cerrable) {
        activas.put(origen, conexion)?.let { anterior ->
            if (anterior !== conexion) runCatching { anterior.cerrar() }
        }
    }

    /** Da de baja [conexion] si sigue siendo la activa (si ya la reemplazaron, no toca nada). */
    fun soltar(origen: String, conexion: Cerrable) {
        activas.remove(origen, conexion)
    }
}
