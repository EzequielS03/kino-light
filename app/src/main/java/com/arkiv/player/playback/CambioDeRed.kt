package com.arkiv.player.playback

/**
 * Cuándo un cambio de red deja MUERTAS las conexiones al origen que ya estaban abiertas.
 *
 * Hasta ahora la app no se enteraba de nada: no había un solo `ConnectivityManager` en el código. Y
 * el precio está escrito en las constantes de [PoliticaOrigen]: el plazo de lectura del CUERPO es de
 * 90 s en archive y 30 s en magis, y es generoso A PROPÓSITO —"un bache de red a mitad de
 * reproducción se recupera solo; cortar rápido acá no arregla nada, rompe la película"—.
 *
 * Ese razonamiento vale para un bache. No vale cuando el celular pasa de WiFi a datos: ahí el socket
 * viejo quedó atado a una interfaz que ya no existe y no se va a recuperar nunca, así que esos 90 s
 * son 90 s de imagen congelada ANTES de que empiece siquiera el primer reintento. La diferencia
 * entre las dos situaciones no se puede deducir desde adentro de la lectura — hay que mirar la red.
 *
 * Portado del reporte de estado del sistema de la app original, que le avisa a su motor de entrega
 * de los cambios de red (`SYS_EVENT_TYPE_NET` con `wired`/`wlan`/`cellular`, en `vc/EnumC5904d` y
 * `dd/AbstractC3049d`) además de pantalla, primer plano y Doze.
 *
 * Función pura porque el modo de fallar es caro en los dos sentidos: pasarse de sensible corta
 * conexiones sanas en cada `onCapabilitiesChanged` (que Android emite constantemente), y quedarse
 * corto deja el cuelgue que esto viene a matar.
 */
object CambioDeRed {

    /**
     * Si pasar de la red [anterior] a la red [actual] invalida lo que ya estaba abierto.
     *
     * Los identificadores son `Network.networkHandle`; null es "sin red". Se comparan por identidad
     * y no por TIPO de transporte a propósito: cambiar de un WiFi a otro WiFi también mata los
     * sockets, y por transporte eso no se ve.
     */
    fun invalidaConexiones(anterior: Long?, actual: Long?): Boolean {
        // La primera red que vemos no invalida nada: no había nada corriendo contra otra.
        if (anterior == null) return false
        return anterior != actual
    }
}

/**
 * Sigue cuál es la red por la que estamos saliendo y contesta, en cada aviso, si hay que abandonar
 * lo que estaba abierto.
 *
 * Vive aparte del `ConnectivityManager` a propósito: la parte de Android es registrar un callback
 * —cuatro líneas, nada que decidir— y la parte que se puede equivocar es esta. Sobre todo el
 * `onLost` de una red que NO estábamos usando: Android avisa la caída de CUALQUIER red, así que con
 * datos móviles andando y el WiFi apagándose atrás llega un `onLost(wifi)` mientras la reproducción
 * va perfecta. Cortar ahí sería cortar por las dudas.
 *
 * No es seguro entre hilos y no hace falta que lo sea: los callbacks de red llegan todos por el
 * mismo `Handler`.
 */
class SeguidorDeRed {

    private var actual: Long? = null

    /** Apareció (o cambió a) la red [red]. Devuelve si eso invalida lo que estaba abierto. */
    fun alAparecer(red: Long): Boolean {
        val invalida = CambioDeRed.invalidaConexiones(actual, red)
        actual = red
        return invalida
    }

    /**
     * Se cayó la red [red]. Devuelve si eso invalida lo que estaba abierto — o sea, solo cuando la
     * que se cayó era la que estábamos usando.
     */
    fun alPerderse(red: Long): Boolean {
        if (actual != red) return false
        val invalida = CambioDeRed.invalidaConexiones(actual, null)
        actual = null
        return invalida
    }
}
