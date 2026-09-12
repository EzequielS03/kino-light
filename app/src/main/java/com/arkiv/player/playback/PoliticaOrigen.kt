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

    /**
     * Cuánto aguantarle a CADA origen, porque no fallan igual.
     *
     * Este objeto nació midiendo archive.org y por un tiempo su calibración se le aplicó a todo. El
     * 2026-08-11, diagnosticando por qué magis tardaba ~15 s en arrancar, se midió el CDN de magis
     * (`yuwc.swzablvpm.com`) y resultó ser el origen OPUESTO:
     *
     * | | archive.org | magis |
     * |---|---|---|
     * | peor caso sano | 72,3 s hasta el primer byte | 0,82 s (3 conexiones a la vez) |
     * | cómo falla | tarda muchísimo, pero llega | no contesta NADA, nunca |
     *
     * En magis el caso "lento pero llega" no existe: contesta en menos de un segundo o está muerto.
     * Con la calibración de archive, cada conexión muerta costaba **20,1 s de espera** — medido en
     * device, con el reintento contestando en 101 ms justo después.
     *
     * [respuesta] y [cuerpo] son dos cosas distintas metidas en el mismo `readTimeout` de
     * `HttpURLConnection`, y separarlas es lo que permite bajar la primera sin romper la segunda:
     * un origen que no contesta en 2 s está muerto, pero un stream que se queda 2 s sin datos a
     * mitad de película es un bache de WiFi de lo más normal.
     */
    enum class Perfil(
        val conectarMs: Int,
        internal val respuesta: IntArray,
        internal val cuerpo: Int,
        internal val esperaBase: Long,
        /**
         * Si se le pueden reciclar sockets del pool de keep-alive.
         *
         * Magis dice que no, y el motivo es la hipótesis que explica por qué `curl` nunca reprodujo
         * el cuelgue (socket nuevo cada vez, 26 tiros sin colgar) mientras en device colgaba
         * siempre la conexión abierta justo después de que `precalentar` abandonara un `bytes=0-`
         * de 209 MB habiendo leído 2 MB: un cuerpo sin drenar volviendo al pool deja el siguiente
         * pedido leyendo restos en vez de cabeceras. Reusar le ahorra un apretón de manos de
         * ~100 ms; el cuelgue cuesta segundos.
         */
        val reusaSockets: Boolean,
    ) {
        /** 20 s → 45 s → 90 s. El último cubre con margen los 72,3 s medidos. */
        ARCHIVE(15_000, intArrayOf(20_000, 45_000, 90_000), 90_000, 400L, true),

        /**
         * 4s -> 10s -> 20s. The first deadline stays short on purpose -- the whole point here is
         * rolling the dice again right away, not waiting -- but the later ones give the CDN the
         * time it genuinely takes: it's been measured answering the same range anywhere from
         * 0.2s to 20s.
         *
         * They used to be a flat 3s, calibrated so the whole budget (9.65s) fit inside the 10s the
         * old "no picture -> software" rescue took to fire. That invariant DIED once magis moved to
         * ExoPlayer: there's no longer a media reload to beat to the punch, and what was left was a
         * tight deadline strangling healthy requests. Measured on the Fire Stick on 2026-08-22: two
         * ranges "rejected by the origin" at 3.002s and 3.004s -- this timer, not the CDN, cutting
         * them off -- cost 18s of waiting before the first picture. This is the third time this
         * number has come up short (2s -> 3s -> here).
         *
         * The waits BETWEEN attempts stay short (50ms -> 150ms -> 450ms): archive's long step
         * exists so as not to punish a saturated node answering 503, and this CDN doesn't throttle
         * us that way -- what limits us is the portal, and the gateway handles that.
         */
        MAGIS(5_000, intArrayOf(4_000, 10_000, 20_000), 30_000, 50L, false),

        /**
         * El mismo CDN, pero para la SONDA DE DURACIÓN, que juega otro juego.
         *
         * Compartían perfil —"una sola fuente de verdad"— y tenía sentido mientras los dos querían
         * lo mismo. Ya no: el proxy está sirviendo la reproducción y le conviene insistir, mientras
         * que la sonda BLOQUEA EL ARRANQUE (cada segundo suyo es un segundo de spinner) y lo que
         * busca es una duración que, si no llega, solo cuesta una barra sin total. Al alargar los
         * plazos de [MAGIS] su peor caso pasó de 12,1 s a 28,1 s, o sea se salía del presupuesto y
         * se cancelaba: la barra se quedaba sin duración justo en el caso que sí tenía arreglo.
         *
         * Así que se queda con los 3 s planos de siempre, que es lo que cabe en su presupuesto (ver
         * TsDurationProbeTest). Contra este CDN abandonar rápido y volver a tirar los dados gana.
         */
        MAGIS_SONDA(5_000, intArrayOf(3_000, 3_000, 3_000), 30_000, 50L, false),
    }

    /** Intentos contra el origen antes de rendirse. */
    const val INTENTOS = 3

    /**
     * Timeout de conexión, igual en todos los intentos: lo lento no es el apretón de manos.
     * Medido en el mismo caso: conexión 4,28 s contra 72,3 s hasta el primer byte.
     */
    const val CONECTAR_MS = 15_000

    fun intentos(perfil: Perfil = Perfil.ARCHIVE): Int = perfil.respuesta.size

    /**
     * Cuánto esperar LA RESPUESTA (las cabeceras) en este intento.
     *
     * Crece con cada intento: contra un origen lento, repetir la misma fecha límite corta es
     * repetir el mismo fracaso. El presupuesto completo de archive queda en ~160 s: es mucho para
     * una pantalla en blanco, pero es el precio de no descartar un origen que sí iba a contestar, y
     * solo se paga entero cuando el origen acepta la conexión y después se queda mudo.
     */
    fun respuestaMs(intento: Int, perfil: Perfil = Perfil.ARCHIVE): Int =
        perfil.respuesta[intento.coerceIn(0, perfil.respuesta.lastIndex)]

    /**
     * Cuánto se aguanta un hueco LEYENDO EL CUERPO, ya con la respuesta en la mano.
     *
     * Es generoso a propósito y no tiene nada que ver con [respuestaMs]: acá ya sabemos que el
     * origen está vivo y sirviendo, y un bache de red a mitad de reproducción se recupera solo.
     * Cortar rápido acá no arregla nada — rompe la película.
     */
    fun cuerpoMs(perfil: Perfil = Perfil.ARCHIVE): Int = perfil.cuerpo

    /**
     * Espera antes del siguiente intento: en archive 400 ms → 1,2 s → 3,6 s.
     *
     * Antes eran 400 ms fijos. Contra un nodo saturado —que es precisamente el que devuelve 503—
     * tres intentos en 1,2 s son tres golpes seguidos al que ya avisó que no da abasto. El primero
     * sigue siendo corto porque un "no" esporádico se recupera enseguida y no hay que castigar el
     * caso bueno.
     */
    fun esperaMs(intento: Int, perfil: Perfil = Perfil.ARCHIVE): Long {
        var ms = perfil.esperaBase
        repeat(intento.coerceIn(0, perfil.respuesta.lastIndex)) { ms *= 3 }
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
