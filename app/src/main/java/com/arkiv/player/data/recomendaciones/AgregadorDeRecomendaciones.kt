package com.arkiv.player.data.recomendaciones

import android.util.Log
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.db.RecomendacionEntity
import com.arkiv.player.data.gateway.FuenteDeContenido
import kotlinx.coroutines.CancellationException

/**
 * Agrega a la biblioteca una tarjeta de la fila "Para ti".
 *
 * La parte con red y escritura del guardado; toda decisión sale de [GuardadoDeRecomendacion], que es
 * pura y sí se prueba. Mismo reparto que `BuscadorDeCapitulos`, que también junta repositorio y
 * gateway para una sola tarea de fondo.
 *
 * Una serie de Magis entra como TEMPORADA (`addMagisSeason`, un episodio por capítulo) y no como ref
 * suelto: el `ref` de una recomendación de serie apunta a la temporada entera, y guardarlo con
 * `addMagisSource` dejaba el ítem con un solo episodio y en la fila de Películas. Una serie de
 * Caracol entra igual de completa, pero por `addDituSeason`: [GuardadoDeRecomendacion.destino] decide
 * primero de cuál de las dos fuentes es la recomendación.
 */
class AgregadorDeRecomendaciones(
    private val repo: ArkivRepository,
    private val gateway: FuenteDeContenido,
) {

    /**
     * Guarda [rec] y devuelve el id del ítem al que navegar, o null si no hay a qué navegar.
     *
     * Casi siempre null coincide con "no quedó nada guardado", pero no siempre: en el borde de
     * [agregarDeCaracol] donde ni el capítulo elegido se pudo guardar solo, la serie puede haber
     * quedado igual en la biblioteca (la escribió `addDituSeason` antes de fallar en el elegido),
     * solo que sin ese capítulo listo para reproducir.
     */
    suspend fun agregar(rec: RecomendacionEntity): String? = when (val destino = GuardadoDeRecomendacion.destino(rec)) {
        is DestinoDeRecomendacion.Magis -> agregarDeMagis(rec, destino)
        is DestinoDeRecomendacion.Caracol -> agregarDeCaracol(rec, destino)
    }

    private suspend fun agregarDeMagis(rec: RecomendacionEntity, destino: DestinoDeRecomendacion.Magis): String? {
        val temporada = if (GuardadoDeRecomendacion.pideCapitulos(rec)) temporadaDelGateway(rec) else null
        val guardo = if (temporada != null) {
            repo.addMagisSeason(
                contentId = destino.contentId,
                title = rec.titulo,
                capitulos = temporada.capitulos,
                // El ref de la recomendación ES el de la temporada: queda guardado en el ítem y
                // `BuscadorDeCapitulos` puede preguntar por capítulos nuevos más adelante.
                seriesRef = rec.ref,
                posterUrl = rec.posterUrl,
                tmdbId = temporada.tmdbId,
                seasonNumber = temporada.seasonNumber,
            ).isNotEmpty()
        } else {
            // Películas, y series cuyos capítulos no se pudieron listar: el guardado suelto, que es
            // mejor que no guardar nada.
            repo.addMagisSource(
                ref = rec.ref,
                contentId = destino.contentId,
                title = rec.titulo,
                posterUrl = rec.posterUrl,
            ) != null
        }
        return if (guardo) GuardadoDeRecomendacion.itemIdDe(destino) else null
    }

    /**
     * Caracol decide por su ref y no por `rec.tipo`: un `BUNDLE`/`GROUP_OF_BUNDLES` se lista y se
     * guarda entero, como en la búsqueda (`SearchPlayback.playDituSeason`); un `VOD` se guarda solo.
     * El elegido es el primer capítulo: nadie tocó uno, y `addDituSeason` necesita alguno.
     */
    private suspend fun agregarDeCaracol(rec: RecomendacionEntity, destino: DestinoDeRecomendacion.Caracol): String? {
        val tmdbId = rec.tmdbId.takeIf { it > 0 }
        val esSerie = com.arkiv.player.data.ditu.DituRef.decodificar(rec.ref)?.esSerie == true
        val episodeId = if (esSerie) {
            val (capitulos, serie) = capitulosDe(rec) ?: return null
            val lista = capitulos.map { com.arkiv.player.ui.search.capituloDeCaracol(it, serie) }
            val elegido = lista.firstOrNull() ?: return null
            repo.addDituSeason(
                seriesRef = rec.ref,
                title = rec.titulo,
                capitulos = lista,
                elegido = elegido,
                posterUrl = rec.posterUrl.ifBlank { serie?.posterUrl.orEmpty() },
                backdropUrl = serie?.backdropUrl.orEmpty(),
                tmdbId = tmdbId,
                // `rec.titulo` es el de TMDB (lo confirmó la cascada), o sea el canónico.
                tituloCanonico = rec.titulo.takeIf { tmdbId != null },
            ) ?: run {
                // `addDituSeason` ya escribió el ítem y sus capítulos en cuanto encontró un
                // contentId válido (ver su KDoc): el null de acá NO es "no se guardó nada", es que
                // [elegido] -el primero de la lista- no calzó con su propio ref entre los recién
                // guardados. Igual que `SearchPlayback.playDituSeason`, se cae a guardar ESE
                // capítulo solo antes de rendirse.
                repo.addDituSource(
                    ref = elegido.ref,
                    seriesRef = rec.ref,
                    title = rec.titulo,
                    episode = elegido.number,
                    episodeTitle = elegido.title,
                    posterUrl = rec.posterUrl.ifBlank { serie?.posterUrl.orEmpty() },
                    backdropUrl = serie?.backdropUrl.orEmpty(),
                    season = elegido.season,
                    tmdbId = tmdbId,
                    tituloCanonico = rec.titulo.takeIf { tmdbId != null },
                )
            }
        } else {
            repo.addDituSource(
                ref = rec.ref,
                title = rec.titulo,
                posterUrl = rec.posterUrl,
                tmdbId = tmdbId,
                tituloCanonico = rec.titulo.takeIf { tmdbId != null },
            )
        }
        return episodeId?.let { GuardadoDeRecomendacion.itemIdDe(destino) }
    }

    /** Los capítulos de una serie de Caracol, o null si no se pudieron listar. */
    private suspend fun capitulosDe(rec: RecomendacionEntity) = try {
        gateway.episodesConSerie(rec.ref)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "capitulos de Caracol de \"${rec.titulo}\": ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /**
     * Los capítulos según el gateway, o null si no los pudo dar.
     *
     * Un fallo acá NO es terminal: `MagisCatalog.detail` responde 422 para las fuentes que no exponen
     * capítulos (hoy hay recomendaciones que apuntan a archive y a torrent), y un gateway caído no
     * puede dejar sin guardar algo que igual se puede reproducir. [CancellationException] se
     * relanza: tragarla dejaría corriendo una corrutina que su scope ya dio por muerta.
     */
    private suspend fun temporadaDelGateway(rec: RecomendacionEntity): TemporadaDeRecomendacion? = try {
        val (capitulos, serie) = gateway.episodesConSerie(rec.ref)
        GuardadoDeRecomendacion.temporadaDe(capitulos, serie)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "capitulos de \"${rec.titulo}\": ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "ArkivRecom"
    }
}
