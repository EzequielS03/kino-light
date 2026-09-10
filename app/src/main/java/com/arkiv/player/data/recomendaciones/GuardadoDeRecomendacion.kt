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

/**
 * Qué guardar al agregar a la biblioteca una tarjeta de la fila "Para ti". Puro/JVM (se prueba sin
 * Room ni red); quien llama pone la red y la escritura, ver [GuardadorDeRecomendaciones].
 *
 * **Una recomendación de serie es una TEMPORADA, no un capítulo.** El `ref` que arma el gateway
 * para una serie apunta a la temporada entera (lleva `episode: 0` adentro), así que guardarlo tal
 * cual con `addMagisSource` dejaba el ítem con un solo episodio y marcado como película — que es
 * exactamente como entró "My Hero Academia", con 1 de sus 13 capítulos. Los capítulos hay que
 * pedírselos al gateway (`MagisCatalog.detail`) y guardarlos con `addMagisSeason`, igual que hace el botón
 * "Guardar" del diálogo de temporada (`SearchPlayback.magisEpisodeIdDe`).
 */
object GuardadoDeRecomendacion {

    /**
     * Si a esta recomendación hay que pedirle la lista de capítulos antes de guardarla.
     *
     * Se decide por [RecomendacionEntity.tipo] —el que el gateway ya cruzó contra TMDB— y no por la
     * fuente del `ref`: el tipo es un campo de la fila, y la fuente vendría de destripar un token
     * que el gateway firma y la app trata como opaco. Una película que igual se preguntara pagaría
     * un 422 de red por nada.
     */
    fun pideCapitulos(rec: RecomendacionEntity): Boolean = rec.tipo == "tv"

    /**
     * La temporada a guardar, o **null** si no hay ninguna y quien llama tiene que caer al guardado
     * suelto de siempre.
     *
     * Null con lista vacía y no una temporada de cero capítulos: los refs de "Para ti" no son todos
     * de Magis (hoy hay recomendaciones que apuntan a archive y a torrent), y `MagisCatalog.detail`
     * responde 422 para esas fuentes. `addMagisSeason` con la lista vacía no escribe nada, así que
     * sin este null la tarjeta se quedaría sin guardar y sin abrir el detalle.
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
