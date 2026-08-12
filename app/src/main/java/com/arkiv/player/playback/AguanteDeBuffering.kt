package com.arkiv.player.playback

/**
 * Cuándo un buffering que no avanza deja de ser "esperá" y pasa a ser "esto no va a andar".
 *
 * El porqué, medido el 2026-08-10 en el Fire TV: se intentó abrir un capítulo cuyo origen no
 * contestaba, el proxy agotó sus reintentos y devolvió 502 — y el reproductor se quedó en
 * `estado=Buffering pos=0ms pistas=v0/a0/s0` desde las 10:38 hasta pasadas las 10:40, sin imagen,
 * sin error y sin nada que le dijera al usuario que no había nada que esperar. libVLC no siempre
 * emite `EncounteredError` cuando el cuerpo de la respuesta simplemente nunca llega: la cabecera ya
 * salió, así que desde su punto de vista sigue habiendo un stream abierto del que aún no leyó nada.
 * Sin alguien que mida el tiempo, esa espera no termina nunca.
 *
 * Lo delicado no es rendirse: es **cuándo**. Rendirse rápido rompería justo el caso que
 * [PoliticaOrigen] existe para salvar — un archive.org que tarda 72 s en soltar el primer byte pero
 * termina sirviendo la película entera. Por eso el límite no es un número suelto: se calcula a
 * partir del presupuesto de la capa de red, para que el reproductor no pueda rendirse antes que
 * ella. Si alguien sube los timeouts allá, este límite sube solo.
 */
object AguanteDeBuffering {

    /**
     * Lo máximo que puede tardar la capa de red en rendirse sola, sumando todos sus intentos.
     * Se deriva de [PoliticaOrigen] a propósito: son dos números que NO pueden divergir.
     */
    val PRESUPUESTO_RED_MS: Long = (0 until PoliticaOrigen.INTENTOS).sumOf {
        PoliticaOrigen.respuestaMs(it).toLong() + PoliticaOrigen.esperaMs(it)
    }

    /**
     * Margen por encima del presupuesto de red. Cubre lo que la suma no ve: el viaje de vuelta del
     * 502 hasta libVLC, y que el reloj del buffering arranca un poco antes que la petición.
     */
    const val MARGEN_MS = 20_000L

    /** Pasado esto sin avanzar un solo milisegundo, es error y hay que decirlo. */
    val LIMITE_MS: Long = PRESUPUESTO_RED_MS + MARGEN_MS

    /**
     * [msEnBuffering] es cuánto lleva estancado de corrido — el reloj se pone en cero apenas el
     * tiempo de reproducción vuelve a moverse, así que un bache normal nunca se acerca al límite.
     */
    fun hayQueRendirse(msEnBuffering: Long): Boolean = msEnBuffering > LIMITE_MS
}
