package com.arkiv.player.playback

import org.junit.Test

/**
 * Cuándo un cambio de red deja MUERTAS las conexiones que ya estaban abiertas.
 *
 * Hoy la app no se entera de nada: no hay un solo `ConnectivityManager` en todo el código. Y el
 * precio está medido en las propias constantes de [PoliticaOrigen]: el plazo de lectura del CUERPO
 * es de 90 s en archive y 30 s en magis, a propósito —"un bache de red a mitad de reproducción se
 * recupera solo, cortar rápido acá rompe la película"—. Ese razonamiento vale para un bache. Cuando
 * el celular pasa de WiFi a datos, el socket viejo quedó atado a una interfaz que ya no existe: no
 * se va a recuperar nunca, y esos 90 s son 90 s de imagen congelada antes de que empiece siquiera
 * el primer reintento.
 *
 * La app original le reporta esto a su motor de entrega: `SYS_EVENT_TYPE_NET` con
 * `wired`/`wlan`/`cellular` (`vc/EnumC5904d` + `dd/AbstractC3049d`), junto con pantalla, primer
 * plano y Doze.
 *
 * Es una función pura porque el modo de fallar es caro en los dos sentidos: pasarse de sensible
 * corta conexiones sanas en cada parpadeo de `onCapabilitiesChanged` (que Android emite todo el
 * tiempo), y quedarse corto deja el cuelgue que esto viene a matar.
 */
class CambioDeRedTest {

    /** Identificadores de red (`Network.networkHandle`); da igual el valor, importa que difieran. */
    private val wifi = 100L
    private val datos = 200L

    @Test fun `pasar de una red a otra invalida lo abierto`() {
        assert(CambioDeRed.invalidaConexiones(anterior = wifi, actual = datos))
        assert(CambioDeRed.invalidaConexiones(anterior = datos, actual = wifi))
    }

    /** Quedarse sin red: lo abierto está muerto y hay que dejar de esperarlo. */
    @Test fun `perder la red invalida lo abierto`() {
        assert(CambioDeRed.invalidaConexiones(anterior = wifi, actual = null))
    }

    /**
     * La PRIMERA red que se ve no invalida nada: no había ninguna conexión corriendo contra otra.
     * Sin esto, arrancar la app cerraría las conexiones del primer arranque de reproducción.
     */
    @Test fun `la primera red que se ve no invalida nada`() {
        assert(!CambioDeRed.invalidaConexiones(anterior = null, actual = wifi))
        assert(!CambioDeRed.invalidaConexiones(anterior = null, actual = null))
    }

    /**
     * El mismo aviso repetido no cuenta. `onCapabilitiesChanged` se dispara constantemente —cada
     * cambio de señal, de validación de internet, de si está medida— y todos llegan con la MISMA
     * red. Reaccionar a cada uno sería cortar la descarga cada pocos segundos.
     */
    @Test fun `el mismo aviso repetido no invalida nada`() {
        assert(!CambioDeRed.invalidaConexiones(anterior = wifi, actual = wifi))
    }

    /** Un parpadeo (se pierde y vuelve la MISMA red) invalida una sola vez, al perderla. */
    @Test fun `un parpadeo de la misma red invalida solo al perderla`() {
        assert(CambioDeRed.invalidaConexiones(anterior = wifi, actual = null))
        // Y al volver, el estado anterior ya es "sin red": no se corta de nuevo.
        assert(!CambioDeRed.invalidaConexiones(anterior = null, actual = wifi))
    }
}

/**
 * El seguimiento de cuál es la red de ahora, que es donde viven las sutilezas.
 *
 * Va aparte del `ConnectivityManager` a propósito: la parte de Android es registrar un callback
 * (cuatro líneas, nada que decidir) y la parte que se puede equivocar es esta — sobre todo el
 * `onLost` de una red que NO era la que estábamos usando, que llega igual y no tiene que cortar
 * nada.
 */
class SeguidorDeRedTest {

    private val wifi = 100L
    private val datos = 200L

    @Test fun `el primer WiFi que aparece no corta nada`() {
        assert(!SeguidorDeRed().alAparecer(wifi))
    }

    @Test fun `pasar de WiFi a datos corta`() {
        val s = SeguidorDeRed()
        s.alAparecer(wifi)
        assert(s.alAparecer(datos))
    }

    @Test fun `el mismo aviso repetido no corta`() {
        val s = SeguidorDeRed()
        s.alAparecer(wifi)
        assert(!s.alAparecer(wifi))
        assert(!s.alAparecer(wifi))
    }

    @Test fun `perder la red que estabamos usando corta`() {
        val s = SeguidorDeRed()
        s.alAparecer(wifi)
        assert(s.alPerderse(wifi))
    }

    /**
     * LA SUTILEZA. Android avisa el `onLost` de CUALQUIER red que se caiga, no solo de la que
     * estamos usando: con datos móviles activos y el WiFi apagándose atrás, llega un `onLost(wifi)`
     * mientras la reproducción va perfecta por datos. Cortar ahí sería cortar por las dudas.
     */
    @Test fun `perder OTRA red no corta nada`() {
        val s = SeguidorDeRed()
        s.alAparecer(wifi)
        s.alAparecer(datos)   // ahora vamos por datos
        assert(!s.alPerderse(wifi))
    }

    /** Después de perderla, volver a la misma red no corta: ya no quedaba nada abierto contra ella. */
    @Test fun `volver despues de perderla no corta`() {
        val s = SeguidorDeRed()
        s.alAparecer(wifi)
        s.alPerderse(wifi)
        assert(!s.alAparecer(wifi))
    }

    /** Perder dos veces la misma red (avisos duplicados) corta una sola vez. */
    @Test fun `perderla dos veces corta una sola vez`() {
        val s = SeguidorDeRed()
        s.alAparecer(wifi)
        assert(s.alPerderse(wifi))
        assert(!s.alPerderse(wifi))
    }
}
