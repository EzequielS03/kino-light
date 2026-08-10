package com.arkiv.player.playback

/**
 * Cuánto aguantarle a archive.org y cuándo vale la pena volver a preguntar.
 *
 * El porqué, medido el 2026-08-10 contra el ítem `tpo-neon-genesis-evangelion-05-…`:
 * el nodo tardó **72,3 s hasta el primer byte** y después sirvió un 206 perfecto. Con el
 * `readTimeout = 20000` fijo que había, los tres intentos morían por timeout mucho antes de que el
 * nodo llegara a contestar, el proxy devolvía 502 y la película no arrancaba nunca — aunque estaba
 * entera y disponible del otro lado. No era un fallo de red ni del reproductor: era la app
 * rindiéndose antes de tiempo.
 *
 * De ahí las dos ideas de este objeto:
 *
 * 1. **El timeout de lectura crece con cada intento.** Contra un origen lento, insistir con la misma
 *    fecha límite corta es repetir el mismo fracaso tres veces. Cada intento le da más aire, y el
 *    último cubre el peor caso medido. El primero se mantiene corto para que un origen realmente
 *    muerto no tenga a nadie mirando la pantalla varios minutos.
 *
 * 2. **Un timeout NO es un rechazo.** El código trataba igual "me contestó que no" (503) y "no llegó
 *    a contestar" (-1), y peor: reintentaba también los 404. Un 404 no cambia por insistir — el
 *    archivo no está — así que reintentarlo es tirar tres timeouts para llegar al mismo lugar. Y es
 *    justo la señal de que archive renombró el archivo y hay que revalidar la metadata.
 */
object PoliticaOrigen {

    /** Intentos contra el origen antes de rendirse. */
    const val INTENTOS = 3

    /**
     * Timeout de conexión, igual en todos los intentos: lo lento no es el apretón de manos.
     * Medido en el mismo caso: conexión 4,28 s contra 72,3 s hasta el primer byte.
     */
    const val CONECTAR_MS = 15_000

    /**
     * Timeout de lectura por intento: 20 s → 45 s → 90 s.
     *
     * El último supera con margen los 72,3 s medidos. El presupuesto completo (lecturas + esperas)
     * queda en ~160 s: es mucho para una pantalla en blanco, pero es el precio de no descartar un
     * origen que sí iba a contestar, y solo se paga entero cuando el origen acepta la conexión y
     * después se queda mudo.
     */
    private val LEER_MS = intArrayOf(20_000, 45_000, 90_000)

    fun leerMs(intento: Int): Int = LEER_MS[intento.coerceIn(0, LEER_MS.lastIndex)]

    /**
     * Espera antes del siguiente intento: 400 ms → 1,2 s → 3,6 s.
     *
     * Antes eran 400 ms fijos. Contra un nodo saturado —que es precisamente el que devuelve 503—
     * tres intentos en 1,2 s son tres golpes seguidos al que ya avisó que no da abasto. El primero
     * sigue siendo corto porque un "no" esporádico se recupera enseguida y no hay que castigar el
     * caso bueno.
     */
    fun esperaMs(intento: Int): Long {
        var ms = 400L
        repeat(intento.coerceIn(0, LEER_MS.lastIndex)) { ms *= 3 }
        return ms
    }

    /**
     * Si este código merece otro intento.
     *
     * Solo se reintenta lo que puede cambiar solo: que no haya contestado (-1), que el servidor
     * esté con problemas (5xx) o que nos esté frenando (429). Todo lo demás —404 y 410 porque el
     * recurso no está, 4xx de permisos porque no se arreglan en 400 ms, y los éxitos— se responde
     * en el acto sin gastar intentos.
     */
    fun valeReintentar(code: Int): Boolean = code == SIN_RESPUESTA || code == 429 || code in 500..599

    /** Código que usa el proxy cuando la conexión murió sin llegar a dar una respuesta. */
    const val SIN_RESPUESTA = -1
}
