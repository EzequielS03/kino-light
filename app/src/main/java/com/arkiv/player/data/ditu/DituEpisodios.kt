package com.arkiv.player.data.ditu

import org.json.JSONObject

/** Un capítulo de Caracol. El [contentId] es lo único que hace falta para resolverlo. */
internal data class DituEpisodio(
    val numero: Int,
    val temporada: Int,
    val titulo: String,
    val contentId: String,
) {
    fun ref(): String = DituRef(contentId, "VOD").codificar()
}

/** Una temporada con lo que hace falta para pintar su pantalla. */
internal data class DituTemporada(
    val episodios: List<DituEpisodio>,
    val tituloSerie: String = "",
    val posterUrl: String = "",
    val fondoUrl: String = "",
    val temporada: Int = 1,
)

/**
 * Los capítulos de una serie de Caracol.
 *
 * Hay dos formas y no se parecen. Un `BUNDLE` es UNA temporada y sus capítulos vienen dentro del
 * detalle. Un `GROUP_OF_BUNDLES` es una serie de varias temporadas: hay que pedir sus bundles
 * hijos y aplanar.
 *
 * En ese segundo caso, **el número de temporada es la posición del bundle en la lista de hijos**,
 * no el `season` del episodio: los bundles de un grupo suelen venir todos con `season: 1` y creerles
 * haría que las temporadas se pisen entre sí.
 */
internal class DituEpisodios(private val cliente: DituClienteLike) {

    suspend fun de(ref: DituRef): DituTemporada =
        if (ref.contentType == "GROUP_OF_BUNDLES") deGrupo(ref.contentId) else deBundle(ref.contentId, 0)

    private suspend fun deGrupo(groupId: String): DituTemporada {
        val hijos = cliente.get(
            DituCatalogo.TRAY,
            mapOf("filter_parentId" to groupId, "filter_contentType" to "BUNDLE"),
        )
        val ids = DituCatalogo.contenedoresDe(hijos).mapNotNull { it.optString("id").takeIf { s -> s.isNotBlank() } }
        val todos = mutableListOf<DituEpisodio>()
        var primera: DituTemporada? = null
        ids.forEachIndexed { indice, bundleId ->
            val t = deBundle(bundleId, temporadaForzada = indice + 1)
            if (primera == null) primera = t
            todos += t.episodios
        }
        val cabeza = primera
        return DituTemporada(
            episodios = todos,
            tituloSerie = cabeza?.tituloSerie.orEmpty(),
            posterUrl = cabeza?.posterUrl.orEmpty(),
            fondoUrl = cabeza?.fondoUrl.orEmpty(),
            temporada = 1,
        )
    }

    /** [temporadaForzada] en 0 significa "usa la que diga el episodio". */
    private suspend fun deBundle(bundleId: String, temporadaForzada: Int): DituTemporada {
        val detalle = cliente.get("CONTENT/DETAIL/BUNDLE/$bundleId")
        val externo = DituCatalogo.contenedoresDe(detalle).firstOrNull()
            ?: return DituTemporada(emptyList())
        val crudos = externo.optJSONArray("containers")
        val episodios = buildList {
            for (i in 0 until (crudos?.length() ?: 0)) {
                val ep = crudos!!.optJSONObject(i) ?: continue
                add(episodioDe(ep, temporadaForzada) ?: continue)
            }
        }
        val meta = externo.optJSONObject("metadata") ?: JSONObject()
        return DituTemporada(
            episodios = episodios,
            tituloSerie = meta.optString("title").trim(),
            posterUrl = DituCatalogo.posterDe(externo),
            fondoUrl = DituCatalogo.fondoDe(externo),
            temporada = temporadaForzada.takeIf { it > 0 } ?: (episodios.firstOrNull()?.temporada ?: 1),
        )
    }

    private fun episodioDe(ep: JSONObject, temporadaForzada: Int): DituEpisodio? {
        val id = ep.optString("id").takeIf { it.isNotBlank() } ?: return null
        // Sin assetId no hay nada que reproducir: ofrecerlo sería prometer algo que falla al tocarlo.
        DituCatalogo.assetMaster(ep) ?: return null
        val m = ep.optJSONObject("metadata") ?: JSONObject()
        val numero = m.optInt("episodeNumber", 0)
        return DituEpisodio(
            numero = numero,
            temporada = temporadaForzada.takeIf { it > 0 } ?: m.optInt("season", 1).takeIf { it > 0 } ?: 1,
            titulo = m.optString("episodeTitle").trim().ifBlank { "Episodio $numero" },
            contentId = id,
        )
    }
}
