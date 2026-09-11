package com.arkiv.player.ui

import com.arkiv.player.data.ItemDetail
import com.arkiv.player.data.MagisEntities
import com.arkiv.player.data.NumeracionCodificada
import com.arkiv.player.data.model.Episode

/**
 * Cómo se nombra un capítulo y por dónde vas, en el detalle del TV y en el del celu.
 *
 * Vive acá, compartido, porque las dos pantallas TIENEN que decir lo mismo: antes esto era una
 * función privada del detalle del TV y el del celu no numeraba nada.
 */
object EtiquetaDeCapitulo {

    /**
     * "T1 · E5" / "E5", a partir de los valores sueltos.
     *
     * Se omite el tramo que no se sepa en vez de inventarlo: es preferible "E5" solo antes que un
     * "T1 · E5" que apunte al capítulo equivocado. El orden de preferencia importa: `episode` manda
     * aunque no haya `season` —un capítulo de Magis guardado sin el contexto de la temporada
     * (`MagisEntities.build`, capítulo suelto) queda con `season = null`, aunque los que sí lo tienen
     * (`buildSeason`) ya numeran "T1 · E5"— y recién después se cae al `orderIndex`.
     *
     * The `orderIndex` doesn't mean the same thing for every row: torrent and web (both removed
     * from this branch, only legacy rows saved before the pruning still carry their `itemId`
     * prefix) packed the season/episode numbering into it, while archive.org (also removed) used
     * it as a plain 0..N-1 correlative. [NumeracionCodificada] tells them apart by looking at the
     * row's source, not the size of the number — looking at the number is what broke season 0,
     * where special 3 showed up as "E4". That's why it needs [itemId] and [section]. When the
     * source doesn't encode, `orderIndex` is the correlative and is shown 1-based.
     *
     * Recibe los valores sueltos y no un [Episode] porque el héroe del home los tiene así, de una
     * fila de "Continuar viendo" (`ContinueRow`), no como modelo. La regla vive UNA sola vez y las
     * tres superficies —los dos detalles y el héroe— la comparten.
     */
    fun numero(season: Int?, episode: Int?, orderIndex: Int, itemId: String, section: String): String {
        if (season != null && episode != null) return "T$season · E$episode"
        if (episode != null) return "E$episode"
        val codificada = NumeracionCodificada.coordenadas(itemId, section, orderIndex)
        if (codificada != null) return "T${codificada.first} · E${codificada.second}"
        // orderIndex doesn't mean the same thing for every source: archive.org and torrent packs
        // (both removed from this branch; this only matters for legacy rows saved before the
        // pruning) distributed it with mapIndexed (0..N-1), but Magis stores the chapter number
        // as-is (`MagisEntities.capituloDe`: `orderIndex = number`). Adding one to a Magis item
        // shifted the whole chapter: Dragon Ball's e126 showed up as "E127" in the home hero, in
        // the detail screen and on the "Play" button. Chapters saved by the current version carry
        // `episode` and take the branch above without reaching here; the old ones have it null
        // and are the ones this count depends on.
        if (itemId.startsWith(MagisEntities.PREFIX)) return "E$orderIndex"
        return "E${orderIndex + 1}"
    }

    /** "T1 · E5" / "E5" para un episodio ya cargado como modelo. Ver la versión de valores sueltos. */
    fun numero(ep: Episode): String = numero(ep.season, ep.episode, ep.orderIndex, ep.itemId, ep.section)

    /**
     * "T1 · E5  ·  La conspiración": el número y, AL LADO, el nombre real del capítulo.
     *
     * El número nunca se reemplaza por el nombre. Identifica el capítulo que se va a reproducir y
     * sigue siendo el dato cierto aunque el cruce con TMDB quede corrido para esa temporada; el
     * nombre es lo que se agrega, no lo que sustituye. Sin esta regla, la fila del detalle del celu
     * mostraba solo "Panzy" donde antes decía "E5  Daima T1_5" y no había forma de saber cuál era.
     *
     * Sin nombre resuelto ([nombre] null o en blanco) se cae al [Episode.displayName], que es el
     * nombre del archivo y en las fuentes que numeran (Magis, packs) ya trae el número adentro.
     */
    fun conNombre(ep: Episode, nombre: String?): String {
        val limpio = nombre?.trim().orEmpty()
        return if (limpio.isEmpty()) ep.displayName else "${numero(ep)}  ·  $limpio"
    }

    /**
     * "Vas en E5  ·  20 episodios", o "20 episodios" si todavía no empezaste.
     *
     * [unidad] es "episodios" (TV) o "videos" (celu), que es como los llama hoy cada pantalla.
     */
    fun avance(detail: ItemDetail, unidad: String): String {
        val total = "${detail.episodes.size} $unidad"
        if (detail.progress.isEmpty() || detail.episodes.size <= 1) return total
        val donde = detail.resumeEpisode ?: return total
        return "Vas en ${numero(donde)}  ·  $total"
    }

    /**
     * "Reproducir" o "Reproducir E5".
     *
     * Nombra el capítulo que el botón va a reproducir de verdad ([ItemDetail.resumeEpisode]), que no
     * siempre es el que estás viendo: si terminaste el E5, reproduce el E6. En una película (un solo
     * episodio) no hay nada que numerar, y sin progreso todavía tampoco -- ahí no alcanza con mirar
     * si `resumeEpisode` da null, porque en cuanto hay episodios SIEMPRE devuelve uno (el primero).
     */
    fun botonReproducir(detail: ItemDetail): String {
        if (detail.episodes.size <= 1 || detail.progress.isEmpty()) return "Reproducir"
        val donde = detail.resumeEpisode ?: return "Reproducir"
        return "Reproducir ${numero(donde)}"
    }

    /** Por debajo de esto no queda nada útil que decir del tiempo restante. */
    private const val RESTANTE_MINIMO_MS = 60_000L

    /**
     * La línea de datos del capítulo para el héroe del home: "T1 · E5  ·  La conspiración  ·  te
     * faltan 12 min".
     *
     * **Cada tramo se omite si no se sabe, nunca se inventa.** El héroe es lo primero que se lee en
     * la pantalla, así que un dato inventado ahí es peor que un dato ausente:
     *  - el **nombre** depende de que TMDB haya cruzado ese capítulo (`episode_still`);
     *  - el **tiempo** depende de conocer la duración, y en Magis la sonda tarda: `durationMs` llega
     *    en 0 hasta que resuelve. También se omite a menos de un minuto del final, donde "te falta
     *    1 min" no ayuda a decidir nada.
     *
     * En una **película** no hay número: "Continuar viendo" también trae películas a medias, y
     * numerarlas dejaría un "E1" absurdo debajo del título. Ahí queda solo el tiempo, que es
     * justamente lo que se quiere saber de una película empezada.
     *
     * Devuelve "" cuando no queda ningún tramo (una película sin duración conocida); quien la use
     * decide qué hacer con eso — las dos pantallas simplemente no dibujan la línea.
     */
    fun lineaDeHeroe(
        esPelicula: Boolean,
        season: Int?,
        episode: Int?,
        orderIndex: Int,
        itemId: String,
        section: String,
        nombre: String?,
        positionMs: Long,
        durationMs: Long,
    ): String {
        val tramos = mutableListOf<String>()
        if (!esPelicula) {
            tramos += numero(season, episode, orderIndex, itemId, section)
            nombre?.trim()?.takeIf { it.isNotEmpty() }?.let { tramos += it }
        }
        val restante = durationMs - positionMs
        if (durationMs > 0 && restante >= RESTANTE_MINIMO_MS) {
            // formatRuntime ya sabe pasar a horas por encima de los 60 minutos ("1 h 26 min"): sin
            // esto, una película recién empezada decía "te faltan 118 min".
            tramos += "te faltan ${formatRuntime(restante / 1000.0)}"
        }
        return tramos.joinToString("  ·  ")
    }
}
