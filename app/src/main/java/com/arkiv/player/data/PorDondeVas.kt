package com.arkiv.player.data

/** El progreso de UN capítulo, con lo mínimo que necesita [PorDondeVas] para decidir. */
data class ProgresoDeCapitulo(
    val episodeId: String,
    val positionMs: Long,
    val watched: Boolean,
    val lastPlayedAt: Long,
)

/**
 * El progreso de un capítulo junto al ítem al que pertenece y al capítulo que le sigue, que es la
 * forma en que sale de la base para armar la fila del home de una sola pasada (ver
 * `PlaybackDao.observeProgresoConSiguiente`).
 */
data class ProgresoEnItem(
    val itemId: String,
    val progreso: ProgresoDeCapitulo,
    val siguienteEpisodeId: String?,
)

/** El capítulo que hay que ofrecer, y desde cuándo cuenta la serie como "tocada". */
data class CapituloAOfrecer(
    val episodeId: String,
    /**
     * El `lastPlayedAt` del ANCLA (lo último que reprodujiste de la serie), no el del capítulo
     * ofrecido —que cuando [esSiguiente] no tiene ninguno—. Es lo que ordena la fila del home.
     */
    val lastPlayedAt: Long,
    /**
     * true si el ofrecido es el capítulo que SIGUE al ancla, es decir: terminaste el anterior y
     * este no lo tocaste nunca.
     *
     * NO significa "no tiene posición guardada". Un capítulo que abriste y cerraste a los tres
     * segundos también arranca en cero, pero es donde estás parado, y el detalle lo marca como
     * actual (ver `ItemDetail.inProgressEpisode`). La diferencia importa: el ancla es un capítulo
     * en el que estás, el siguiente es uno al que todavía no llegaste.
     */
    val esSiguiente: Boolean,
)

/**
 * Por dónde vas en una serie.
 *
 * Vive acá, junto a [UmbralDeVisto] y por el mismo motivo: es una regla de producto que alimenta DOS
 * superficies —la fila "Continuar viendo" del home y el botón "Reproducir" del detalle— y tenerla en
 * dos lugares es exactamente cómo se rompió antes.
 *
 * ### El ancla es lo último que reprodujiste, TERMINADO O NO
 *
 * La regla anterior buscaba "el capítulo más reciente SIN TERMINAR". Reportado en device
 * (2026-08-13) y verificado contra la base del Fire TV: Dragon Ball visto hasta el e136 —127 a 136
 * todos terminados esa noche— más un e104 abandonado al 38% la mañana anterior. Como el e104 era el
 * único sin terminar, era "el más reciente sin terminar", así que el home lo ponía de tarjeta de
 * Dragon Ball y el detalle ofrecía reproducirlo: las dos superficies te mandaban treinta capítulos
 * para atrás, y la serie que estabas viendo de verdad no aparecía por ningún lado.
 *
 * Acá el ancla es la reproducción más reciente a secas. Si esa quedó a medias, es por donde vas; si
 * la terminaste, vas por la que sigue. Un capítulo abandonado no puede resucitar porque, salvo que
 * sea lo último que tocaste, nunca es el ancla.
 *
 * ### Los capítulos solo ABIERTOS no desplazan a los reproducidos
 *
 * `marcarEnCurso` escribe una fila en `positionMs == 0` con solo abrir un capítulo. Sin la
 * preferencia por los que tienen posición, abrir tres capítulos sin que suenen convertía al último
 * en "por dónde voy" por delante del que sí venías viendo (medido en Dragon Ball el 2026-08-12: el
 * e126 con 3:30 perdía contra el e127 y el e128, abiertos después y en 0). Por eso el ancla se busca
 * primero entre los que tienen reproducción real, y los solo-abiertos quedan de respaldo.
 *
 * ### Acá NO hay piso de segundos
 *
 * [elegir] contesta "por dónde vas" y punto: un capítulo con dos segundos reproducidos es por donde
 * vas si es lo último que tocaste. El piso ("no me llenes el home con lo que abrí tres segundos")
 * es una regla de la FILA, no de la serie, y por eso vive en [porItem]. Meterlo acá adentro
 * reviviría el bug que arregló `inProgressEpisode`: darle play al E5, salir a los tres segundos y
 * que el detalle te diga "vas en el E1".
 */
object PorDondeVas {

    /**
     * @param progresos todas las filas de progreso de UN ítem (las de otros ítems no van acá).
     * @param siguienteDe dado un episodeId, el que le sigue en la lista del ítem, o null si es el
     *   último. La lista vive en distinto lugar según quién llame (el detalle la tiene en memoria,
     *   el home la resuelve en SQL), así que entra como función y no como lista.
     * @return el capítulo a ofrecer, o null si no hay nada que continuar de este ítem.
     */
    fun elegir(
        progresos: List<ProgresoDeCapitulo>,
        siguienteDe: (String) -> String?,
    ): CapituloAOfrecer? {
        if (progresos.isEmpty()) return null
        val conReproduccion = progresos.filter { it.positionMs > 0 }
        // El desempate por episodeId no es cosmético: dos filas con el mismo lastPlayedAt (sync,
        // relojes que empatan al milisegundo) tienen que dar SIEMPRE la misma respuesta, o la
        // tarjeta del home cambia sola entre emisiones.
        val ancla = conReproduccion.ifEmpty { progresos }
            .maxWithOrNull(compareBy({ it.lastPlayedAt }, { it.episodeId })) ?: return null

        return if (!ancla.watched) {
            CapituloAOfrecer(ancla.episodeId, ancla.lastPlayedAt, esSiguiente = false)
        } else {
            // Lo terminaste: va el que sigue. Si no hay siguiente, terminaste la serie (o era una
            // película) y no hay nada que continuar.
            siguienteDe(ancla.episodeId)
                ?.let { CapituloAOfrecer(it, ancla.lastPlayedAt, esSiguiente = true) }
        }
    }

    /**
     * La fila "Continuar viendo" entera: UNA tarjeta por ítem, de la serie tocada más recién a la
     * más vieja.
     *
     * Una tarjeta por ÍTEM y no por capítulo porque la consulta trae una fila por cada capítulo con
     * progreso, y así una sola serie llenaba la fila con la misma carátula repetida (GetBackers
     * llegó a nueve tarjetas). Se agrupa por `itemId`, NO por título: dos ítems distintos que
     * casualmente comparten nombre son dos cosas distintas.
     *
     * @param minPositionMs el piso de la fila. Un ítem entra si terminaste algo suyo (entonces lo
     *   venís viendo de verdad, aunque el capítulo actual lleve tres segundos) o si algún capítulo
     *   suyo pasó el piso. Lo que se cae es lo que tocaste un momento y nunca más: una película que
     *   abriste veinte segundos no tiene por qué ocupar la fila para siempre.
     */
    fun porItem(
        filas: List<ProgresoEnItem>,
        minPositionMs: Long,
        limite: Int = 20,
    ): List<CapituloAOfrecer> =
        filas.groupBy { it.itemId }
            .values
            .mapNotNull { delItem ->
                val progresos = delItem.map { it.progreso }
                val loVenisViendo = progresos.any { it.watched || it.positionMs > minPositionMs }
                if (!loVenisViendo) return@mapNotNull null
                val siguientes = delItem.associate { it.progreso.episodeId to it.siguienteEpisodeId }
                elegir(progresos) { siguientes[it] }
            }
            // El desempate por episodeId, igual que en [elegir], es para que la fila no se
            // reordene sola entre emisiones cuando dos series empatan al milisegundo.
            .sortedWith(compareByDescending<CapituloAOfrecer> { it.lastPlayedAt }.thenBy { it.episodeId })
            .take(limite)
}
