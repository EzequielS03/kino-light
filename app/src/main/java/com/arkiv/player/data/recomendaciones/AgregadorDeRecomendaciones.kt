package com.arkiv.player.data.recomendaciones

import android.util.Log
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.db.RecomendacionEntity
import com.arkiv.player.data.gateway.ArkivApiClient
import kotlinx.coroutines.CancellationException

/**
 * Agrega a la biblioteca una tarjeta de la fila "Para ti".
 *
 * La parte con red y escritura del guardado; toda decisión sale de [GuardadoDeRecomendacion], que es
 * pura y sí se prueba. Mismo reparto que `BuscadorDeCapitulos`, que también junta repositorio y
 * gateway para una sola tarea de fondo.
 *
 * Una serie entra como TEMPORADA (`addMagisSeason`, un episodio por capítulo) y no como ref suelto:
 * el `ref` de una recomendación de serie apunta a la temporada entera, y guardarlo con
 * `addMagisSource` dejaba el ítem con un solo episodio y en la fila de Películas.
 */
class AgregadorDeRecomendaciones(
    private val repo: ArkivRepository,
    private val gateway: ArkivApiClient,
) {

    /**
     * Guarda [rec] y dice si quedó algo en la biblioteca. En false no hay que navegar al detalle:
     * no encontraría nada y se vería como una recomendación rota.
     */
    suspend fun agregar(rec: RecomendacionEntity): Boolean {
        val temporada = if (GuardadoDeRecomendacion.pideCapitulos(rec)) temporadaDelGateway(rec) else null
        return if (temporada != null) {
            repo.addMagisSeason(
                contentId = rec.id,
                title = rec.titulo,
                capitulos = temporada.capitulos,
                // El ref de la recomendación ES el de la temporada: el mismo que responde
                // `/v1/episodes`, así que queda guardado en el ítem y `BuscadorDeCapitulos` puede
                // preguntar por capítulos nuevos más adelante.
                seriesRef = rec.ref,
                posterUrl = rec.posterUrl,
                tmdbId = temporada.tmdbId,
                seasonNumber = temporada.seasonNumber,
            ).isNotEmpty()
        } else {
            // Películas, y series cuya fuente no sabe listar capítulos (archive, torrent): el
            // guardado de siempre, que es mejor que no guardar nada.
            repo.addMagisSource(
                ref = rec.ref,
                contentId = rec.id,
                title = rec.titulo,
                posterUrl = rec.posterUrl,
            ) != null
        }
    }

    /**
     * Los capítulos según el gateway, o null si no los pudo dar.
     *
     * Un fallo acá NO es terminal: `/v1/episodes` responde 422 para las fuentes que no exponen
     * capítulos (hoy hay recomendaciones que apuntan a archive y a torrent), y un gateway caído no
     * puede dejar sin guardar algo que igual se puede reproducir. [CancellationException] se
     * relanza, mismo criterio que `AvisadorDeRecomendaciones`: tragarla dejaría corriendo una
     * corrutina que su scope ya dio por muerta.
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
