package com.arkiv.player.data.nuevos

import android.util.Log
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.DituEntities
import com.arkiv.player.data.db.ItemDao
import com.arkiv.player.data.gateway.ContentSource
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
 * A quién preguntarle lo decide [SeriesPorRevisar] y qué pedirle [MissingChapters]; las cotas
 * viven ahí porque son lo que hace barato correr esto siempre.
 */
class BuscadorDeCapitulos(
    private val repo: ArkivRepository,
    private val itemDao: ItemDao,
    private val gateway: ContentSource,
    private val ahora: () -> Long = { System.currentTimeMillis() },
) {

    /** Corre una pasada completa. Devuelve cuántos capítulos nuevos aparecieron. */
    suspend fun buscar(): Int = withContext(Dispatchers.IO) {
        val candidatas = runCatching { itemDao.seriesConProgreso() }.getOrNull()
            ?.map { SerieCandidata(it.itemId, it.source, it.ultimoVistoMs, it.episodios) }
            ?: return@withContext 0
        val elegidas = SeriesPorRevisar.elegir(candidatas, ahora())
        if (elegidas.isEmpty()) return@withContext 0
        Log.i(TAG, "checking ${elegidas.size} series out of ${candidatas.size} in the library")

        var nuevos = 0
        for (serie in elegidas) {
            nuevos += runCatching {
                when (serie.fuente) {
                    "magis" -> revisarMagis(serie)
                    "ditu" -> revisarDitu(serie)
                    else -> 0
                }
            }.getOrElse { e ->
                // A series that fails can't take the others down with it.
                Log.i(TAG, "couldn't check ${serie.itemId}: ${e.message}")
                0
            }
        }
        Log.i(TAG, "done: $nuevos new chapter(s)")
        nuevos
    }

    /**
     * Magis es la única fuente cuyos capítulos expone el gateway (`MagisCatalog.detail`; el resto
     * responde 422), así que se le puede preguntar directo.
     *
     * El `ref` guardado **se re-emite en cada búsqueda del portal** (ver `addMagisSource`), así que
     * el nuestro puede estar vencido. Que esto falle es esperable y no es un error del usuario: se
     * registra a nivel info y se sigue.
     *
     * Pide `episodesWithSeries` (no `episodes`) para que un capítulo agregado acá salga enriquecido
     * igual que si se hubiera tocado a mano: still, nombre real, sinopsis y la temporada real del
     * `GatewaySerie`. Esto último no es cosmético — es lo que evita el bug que originó este bloque de
     * parámetros: un capítulo agregado sin `season` deja el ítem con episodios mezclados (unos con
     * temporada puesta, otros no) y `ArkivRepository.ensureEpisodeStills` cae a su rama de aplanar
     * (ver el KDoc de `MagisEntities.capituloDe`), pisando en silencio los stills correctos de toda
     * la temporada. Si el gateway no pudo resolver TMDB (`GatewaySerie` null), todo esto sale null y
     * el capítulo se guarda exactamente como antes: con lo del portal, sin fila de still.
     */
    private suspend fun revisarMagis(serie: SerieCandidata): Int {
        val ref = itemDao.getItem(serie.itemId)?.torrentData.orEmpty()
        if (ref.isBlank()) return 0
        val (enLaFuente, gatewaySerie) = gateway.episodesWithSeries(ref)
        if (enLaFuente.isEmpty()) {
            Log.i(TAG, "magis ${serie.itemId}: no chapters (stale ref?)")
            return 0
        }
        val tengo = itemDao.getEpisodesOf(serie.itemId).mapNotNull { it.episode }
        val aPedir = MissingChapters.toFetch(tengo, enLaFuente.map { it.number })
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
                season = gatewaySerie?.seasonNumber,
                // `optInt` en el parseo da 0 si el campo faltara, y un 0 no es null (ver el mismo
                // blindaje en SearchPlayback.playMagisSeason).
                tmdbId = gatewaySerie?.tmdbId?.takeIf { it > 0 },
                still = ep.still,
                tmdbTitle = ep.tmdbTitle,
                overview = ep.overview,
            )
            if (id != null) puestos++
        }
        if (puestos > 0) Log.i(TAG, "magis ${serie.itemId}: +$puestos")
        return puestos
    }

    /**
     * Caracol chapters, the same shape as [revisarMagis] but season-aware: `gateway.episodesWithSeries`
     * (routed to `DituFuente` by `FuenteCompuesta`, since [ref] is a Caracol ref) lists what's on
     * the source today, and [MissingChapters.toFetchBySeason] decides what's actually new.
     *
     * Season-aware on purpose, unlike [revisarMagis]'s plain [MissingChapters.toFetch]: Caracol
     * numbers chapters PER SEASON, so comparing against the highest NUMBER stored would make season
     * 2's chapter 1 look like it's already covered by a season 1 with ten chapters.
     *
     * Filters through [DituEntities.capitulosGuardables] before comparing: a chapter Caracol lists
     * with number 0 can never be saved ([DituEntities.contentIdDelItem] rejects it), so it must
     * never count as missing -- that would retry it forever for nothing.
     *
     * Each missing chapter is saved with [ArkivRepository.addDituSource], which upserts just that
     * one chapter and does NOT re-seal `episodiosVistosEnLista` (unlike `addDituSeason`), so the
     * item's new-chapter badge picks it up -- same as a chapter [revisarMagis] adds.
     */
    private suspend fun revisarDitu(serie: SerieCandidata): Int {
        val item = itemDao.getItem(serie.itemId) ?: return 0
        val ref = item.torrentData.orEmpty()
        if (ref.isBlank()) return 0
        val (enLaFuente, gatewaySerie) = gateway.episodesWithSeries(ref)
        if (enLaFuente.isEmpty()) {
            Log.i(TAG, "ditu ${serie.itemId}: no chapters (stale ref?)")
            return 0
        }

        // Same season rule the save path uses, so a chapter is keyed here exactly as it would be
        // once saved.
        val candidates = enLaFuente.map { ep -> DituEntities.capituloDeCaracol(ep, gatewaySerie) }
        val saveable = DituEntities.capitulosGuardables(ref, candidates)
        if (saveable.isEmpty()) return 0

        // Both sides keyed through DituEntities.temporadaGuardada: the stored `season` column is
        // always written through it (null/0 -> 1), so comparing the raw source season directly
        // would miss a chapter whose source season is null or 0 -- its key would land on `0`,
        // never past a stored high-water mark that's really `1`.
        val have = itemDao.getEpisodesOf(serie.itemId)
            .mapNotNull { ep -> ep.episode?.let { DituEntities.temporadaGuardada(ep.season) to it } }
        val inSource = saveable.map { DituEntities.temporadaGuardada(it.season) to it.number }
        val missing = MissingChapters.toFetchBySeason(have, inSource).toSet()
        if (missing.isEmpty()) return 0

        var added = 0
        for (cap in saveable) {
            if (DituEntities.temporadaGuardada(cap.season) to cap.number !in missing) continue
            val id = repo.addDituSource(
                ref = cap.ref,
                title = item.title,
                episode = cap.number,
                posterUrl = item.thumbnailUrl,
                episodeTitle = cap.title,
                seriesRef = ref,
                season = cap.season,
                tmdbId = gatewaySerie?.tmdbId?.takeIf { it > 0 },
            )
            if (id != null) added++
        }
        if (added > 0) Log.i(TAG, "ditu ${serie.itemId}: +$added")
        return added
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

    private companion object {
        const val TAG = "ArkivNuevos"
    }
}
