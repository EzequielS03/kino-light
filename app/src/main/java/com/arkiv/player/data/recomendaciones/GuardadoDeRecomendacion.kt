package com.arkiv.player.data.recomendaciones

import com.arkiv.player.data.CapituloDeTemporada
import com.arkiv.player.data.db.RecomendacionEntity
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewaySerie

/**
 * La temporada que hay que guardar de una recomendación, ya en el modelo que entiende
 * `ArkivRepository.addMagisSeason`.
 *
 * [tmdbId] y [seasonNumber] son null cuando el gateway no pudo cruzar la serie contra TMDB, y no
 * por eso se deja de guardar: `MagisEntities.buildSeason` los preserva contra lo que ya estuviera
 * en la base en vez de pisarlo.
 */
data class TemporadaDeRecomendacion(
    val capitulos: List<CapituloDeTemporada>,
    val tmdbId: Int?,
    val seasonNumber: Int?,
)

/** A qué fuente va a parar una recomendación al guardarla, y con qué contentId. */
internal sealed interface DestinoDeRecomendacion {
    val contentId: String
    data class Magis(override val contentId: String) : DestinoDeRecomendacion
    data class Caracol(override val contentId: String) : DestinoDeRecomendacion
}

/**
 * Qué guardar al agregar a la biblioteca una tarjeta de la fila "Para ti". Puro/JVM (se prueba sin
 * Room ni red); quien llama pone la red y la escritura, ver [AgregadorDeRecomendaciones].
 *
 * Primero decide de qué FUENTE es la recomendación ([destino], [destinoDeRef], [itemIdDe]: Magis o
 * Caracol, según su `ref`) y después, para Magis, cómo guardarla:
 *
 * **Una recomendación de serie es una TEMPORADA, no un capítulo.** El `ref` de una serie de Magis
 * apunta a la temporada entera (lleva `episode: 0` adentro), así que guardarlo tal cual con
 * `addMagisSource` dejaba el ítem con un solo episodio y marcado como película — que es exactamente
 * como entró "My Hero Academia", con 1 de sus 13 capítulos. Los capítulos hay que pedírselos al
 * portal (`MagisCatalog.detail`) y guardarlos con `addMagisSeason`, igual que hace el botón
 * "Guardar" del diálogo de temporada (`SearchPlayback.magisEpisodeIdDe`). Caracol resuelve su propio
 * camino en `AgregadorDeRecomendaciones.agregarDeCaracol`.
 */
object GuardadoDeRecomendacion {

    /**
     * Si a esta recomendación hay que pedirle la lista de capítulos antes de guardarla.
     *
     * Se decide por [RecomendacionEntity.tipo] —el que ya se cruzó contra TMDB— y solo aplica a
     * Magis: Caracol decide por su propio `ref` (`DituRef.esSerie`, ver
     * `AgregadorDeRecomendaciones.agregarDeCaracol`). Asking for a movie would pay a portal listing
     * call for nothing.
     */
    fun pideCapitulos(rec: RecomendacionEntity): Boolean = rec.tipo == "tv"

    /**
     * De qué fuente es este `ref`, o null si no es de ninguna conocida. Caracol se pregunta primero,
     * pero da igual el orden: `DituRef.decodificar` y `MagisRef.decodificar` solo aceptan lo suyo
     * (su prefijo, o un ref viejo del gateway con su propia fuente adentro).
     */
    internal fun destinoDeRef(ref: String): DestinoDeRecomendacion? {
        com.arkiv.player.data.ditu.DituRef.decodificar(ref)?.let { return DestinoDeRecomendacion.Caracol(it.contentId) }
        com.arkiv.player.data.magis.MagisRef.decodificar(ref)?.let { return DestinoDeRecomendacion.Magis(it.contentId) }
        return null
    }

    /**
     * De qué fuente es [rec]. Un ref de Caracol NUNCA puede guardarse como Magis (el reproductor lo
     * mandaría a `loadMagis`). Una fila vieja con un ref que no se entiende cae a Magis con su id,
     * que es como se guardaba antes.
     */
    internal fun destino(rec: RecomendacionEntity): DestinoDeRecomendacion =
        destinoDeRef(rec.ref) ?: DestinoDeRecomendacion.Magis(rec.id)

    /** El id del ítem que queda en la biblioteca: el mismo que arma la búsqueda para esa fuente. */
    internal fun itemIdDe(destino: DestinoDeRecomendacion): String = when (destino) {
        is DestinoDeRecomendacion.Magis -> com.arkiv.player.data.MagisEntities.itemIdDe(destino.contentId)
        is DestinoDeRecomendacion.Caracol -> com.arkiv.player.data.DituEntities.itemIdDe(destino.contentId)
    }

    /**
     * La temporada a guardar, o **null** si no hay ninguna y quien llama tiene que caer al guardado
     * suelto de siempre.
     *
     * Null for an empty list, not a season with zero chapters: the Magis portal listing can come
     * back empty, and `addMagisSeason` with an empty list writes nothing, so without this null the
     * card would stay unsaved and never open its detail. Caracol refs never get here: `destino()`
     * sends them to `AgregadorDeRecomendaciones.agregarDeCaracol`.
     *
     * Un [GatewaySerie.tmdbId] en 0 es "no vino" y no una identificación: sale de un `optInt`, y ese
     * 0 le ganaría al `?:` con el que `buildSeason` preserva el tmdbId que ya estaba guardado.
     */
    fun temporadaDe(
        capitulos: List<GatewayEpisode>,
        serie: GatewaySerie?,
    ): TemporadaDeRecomendacion? {
        if (capitulos.isEmpty()) return null
        return TemporadaDeRecomendacion(
            capitulos = capitulos.map {
                CapituloDeTemporada(
                    number = it.number,
                    title = it.title,
                    ref = it.ref,
                    still = it.still,
                    tmdbTitle = it.tmdbTitle,
                    overview = it.overview,
                )
            },
            tmdbId = serie?.tmdbId?.takeIf { it > 0 },
            seasonNumber = serie?.seasonNumber,
        )
    }
}
