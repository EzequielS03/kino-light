package com.arkiv.player.data.nuevos

import android.util.Log
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.db.ItemDao
import com.arkiv.player.data.gateway.ArkivApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Busca capítulos nuevos de las series que estás viendo, al abrir la app.
 *
 * Antes de esto, una serie de la biblioteca no se enteraba sola de que salió un capítulo: archive
 * solo se refrescaba si abrías su detalle, y web/magis nunca (sus capítulos entraban únicamente
 * cuando los agregabas a mano desde el catálogo).
 *
 * Es **oportunista**: corre en background, no bloquea nada, y si falla falla en silencio. Que no
 * haya red al abrir la app no es un error que reportarle a nadie — se reintenta en el próximo
 * arranque. Por eso ningún fallo de acá sube: se registran y se sigue con la serie que sigue.
 *
 * A quién preguntarle lo decide [SeriesPorRevisar] y qué pedirle [CapitulosFaltantes]; las cotas
 * viven ahí porque son lo que hace barato correr esto siempre.
 */
class BuscadorDeCapitulos(
    private val repo: ArkivRepository,
    private val itemDao: ItemDao,
    private val gateway: ArkivApiClient,
    private val ahora: () -> Long = { System.currentTimeMillis() },
) {

    /** Corre una pasada completa. Devuelve cuántos capítulos nuevos aparecieron. */
    suspend fun buscar(): Int = withContext(Dispatchers.IO) {
        val candidatas = runCatching { itemDao.seriesConProgreso() }.getOrNull()
            ?.map { SerieCandidata(it.itemId, it.source, it.ultimoVistoMs, it.episodios) }
            ?: return@withContext 0
        val elegidas = SeriesPorRevisar.elegir(candidatas, ahora())
        if (elegidas.isEmpty()) return@withContext 0
        Log.i(TAG, "reviso ${elegidas.size} serie(s) de ${candidatas.size} en biblioteca")

        var nuevos = 0
        for (serie in elegidas) {
            nuevos += runCatching {
                when (serie.fuente) {
                    "archive" -> revisarArchive(serie)
                    "magis" -> revisarMagis(serie)
                    "web" -> revisarWeb(serie)
                    else -> 0
                }
            }.getOrElse { e ->
                // Una serie que falla no puede llevarse puestas a las demás.
                Log.i(TAG, "no se pudo revisar ${serie.itemId}: ${e.message}")
                0
            }
        }
        Log.i(TAG, "listo: $nuevos capítulo(s) nuevo(s)")
        nuevos
    }

    /**
     * Archive es la barata: `refreshItem` ya re-lee `/metadata/` y reemplaza los episodios con lo
     * que haya ahora, así que los nuevos aparecen solos. Lo único que faltaba era llamarlo sin
     * tener que abrir el detalle.
     */
    private suspend fun revisarArchive(serie: SerieCandidata): Int {
        val antes = serie.episodios
        repo.refreshItem(serie.itemId).onFailure {
            Log.i(TAG, "archive ${serie.itemId}: no se pudo refrescar (${it.message})")
            return 0
        }
        val despues = itemDao.getEpisodesOf(serie.itemId).count { !it.deleted }
        val nuevos = (despues - antes).coerceAtLeast(0)
        if (nuevos > 0) Log.i(TAG, "archive ${serie.itemId}: +$nuevos")
        return nuevos
    }

    /**
     * Magis es la única fuente cuyos capítulos expone el gateway (`/v1/episodes`; el resto responde
     * 422), así que se le puede preguntar directo.
     *
     * El `ref` guardado **se re-emite en cada búsqueda del portal** (ver `addMagisSource`), así que
     * el nuestro puede estar vencido. Que esto falle es esperable y no es un error del usuario: se
     * registra a nivel info y se sigue.
     */
    private suspend fun revisarMagis(serie: SerieCandidata): Int {
        val ref = itemDao.getItem(serie.itemId)?.torrentData.orEmpty()
        if (ref.isBlank()) return 0
        val enLaFuente = gateway.episodes(ref)
        if (enLaFuente.isEmpty()) {
            Log.i(TAG, "magis ${serie.itemId}: sin capítulos (¿ref vencido?)")
            return 0
        }
        val tengo = itemDao.getEpisodesOf(serie.itemId).mapNotNull { it.episode }
        val aPedir = CapitulosFaltantes.aPedir(tengo, enLaFuente.map { it.number })
        if (aPedir.isEmpty()) return 0

        val item = itemDao.getItem(serie.itemId) ?: return 0
        var puestos = 0
        for (numero in aPedir) {
            val ep = enLaFuente.firstOrNull { it.number == numero } ?: continue
            val id = repo.addMagisSource(
                ref = ep.ref,
                contentId = contentIdDe(serie.itemId) ?: continue,
                title = item.title,
                episode = numero,
                posterUrl = item.thumbnailUrl,
            )
            if (id != null) puestos++
        }
        if (puestos > 0) Log.i(TAG, "magis ${serie.itemId}: +$puestos")
        return puestos
    }

    /**
     * `magis:<contentId>` o `magis:<contentId>:e<n>` → el contentId.
     *
     * Se saca del identifier y no se guarda aparte porque ya está ahí: el id se derivó de él justo
     * para que sobreviva a los refs que caducan.
     */
    private fun contentIdDe(itemId: String): String? = itemId
        .removePrefix("magis:")
        .substringBefore(":e")
        .takeIf { it.isNotBlank() && itemId.startsWith("magis:") }

    /**
     * TODO(web): pendiente, y es la más cara de las tres.
     *
     * El gateway no expone lista de capítulos para web, así que hace falta: (1) preguntarle a la
     * metadata (TMDB/Cinemeta) qué episodios existen en la temporada en curso, (2) acotar con
     * [CapitulosFaltantes] a los posteriores al máximo que ya se tiene, y (3) por cada uno,
     * `gateway.search(tmdbId, season, episode, sources = "web")` y `addWebSeriesEpisode` si hay
     * resultado. Ver Task 5 del plan
     * `docs/superpowers/plans/2026-08-10-capitulos-nuevos-en-series-seguidas.md`.
     */
    private fun revisarWeb(serie: SerieCandidata): Int {
        Log.i(TAG, "web ${serie.itemId}: todavía no implementado (Task 5 del plan)")
        return 0
    }

    private companion object {
        const val TAG = "ArkivNuevos"
    }
}
