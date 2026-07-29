package com.arkiv.player.remote

/** De dónde viene lo que muestra la barra. */
enum class BarFuente { TV, CAST }

/**
 * Lo que la barra debe dibujar, ya normalizado: viene del Fire TV o del Chromecast y la barra no
 * se entera de cuál.
 */
data class BarState(
    val nowPlaying: TvNowPlaying,
    val receivedAtMs: Long,
    /** Si la posición hay que avanzarla con el reloj local entre lecturas. */
    val extrapolar: Boolean,
    val fuente: BarFuente,
)

/** Marca de arranque de una fuente: qué está sonando y desde cuándo, en el reloj local del celu. */
data class MarcaFuente(val identidad: String? = null, val desdeMs: Long = 0L)

/**
 * Re-sella la marca solo cuando la fuente arrancó algo NUEVO.
 *
 * Cuando la fuente se calla, la marca se CONGELA en vez de borrarse. Un corte momentáneo —la sesión
 * de cast que se suspende por un hipo de WiFi y vuelve sola con lo mismo cargado— no puede contar
 * como arranque nuevo y robarle la barra a la otra fuente. Si una fuente está sonando o no lo decide
 * [BarSource.pick] mirando si hay foto; esta marca solo responde "desde cuándo".
 */
fun sellarMarca(previa: MarcaFuente, identidad: String?, ahoraMs: Long): MarcaFuente = when {
    identidad == null -> previa
    identidad != previa.identidad -> MarcaFuente(identidad, ahoraMs)
    else -> previa
}

/**
 * Elige la fuente y la normaliza. Pura: testeable sin Android.
 *
 * **Gana la que arrancó último, y una saca a la otra de la barra.** Castear tapa lo que muestre el
 * Fire TV; poner algo nuevo en el app del TV recupera la barra aunque el casteo siga vivo. Solo
 * cambia lo que la barra muestra y controla: la otra fuente sigue reproduciendo donde esté.
 *
 * Las marcas son instantes del reloj LOCAL del celu (`0` = esa fuente no está reproduciendo), no
 * relojes de los dispositivos: compararlos exigiría sincronizarlos. Quien las sella es
 * [NowPlayingCoordinator], que es el único que ve las dos fuentes cambiar.
 */
object BarSource {

    fun pick(
        castActivo: Boolean,
        cast: TvNowPlaying?,
        castDesdeMs: Long,
        tv: TvSnapshot?,
        tvDesdeMs: Long,
        ahoraMs: Long,
    ): BarState? {
        val hayCast = castActivo && cast != null
        // El cast se lee del CastPlayer, que está en este teléfono: la posición es de ahora mismo,
        // no hay nada que extrapolar ni latencia que disimular.
        val delCast = { BarState(cast!!, ahoraMs, extrapolar = false, fuente = BarFuente.CAST) }
        // El TV llega por fotos cada 3s: entre una y otra la posición se avanza localmente.
        val delTv = { BarState(tv!!.nowPlaying, tv.receivedAtMs, extrapolar = true, fuente = BarFuente.TV) }
        return when {
            // Empate: manda el Chromecast, que es lo que el usuario tiene a mano para controlar de
            // verdad. Requiere que las dos marcas se sellen en el MISMO tick del coordinador, así
            // que en la práctica es defensivo: al arrancar la app de cero el cast no tiene petición
            // pendiente (vive en memoria y muere con el proceso), no produce foto, y manda el TV.
            hayCast && tv != null -> if (castDesdeMs >= tvDesdeMs) delCast() else delTv()
            hayCast -> delCast()
            tv != null -> delTv()
            else -> null
        }
    }
}
