package com.arkiv.player.data.ditu

import com.arkiv.player.data.gateway.GatewayPlayable
import org.json.JSONObject

/**
 * De un `ref` a algo que el reproductor pueda abrir.
 *
 * Son tres pasos y el orden no es negociable: `DETAIL` da el `assetId`, `USERDATA` dice si te
 * dejan ver, y recién entonces `VIDEOURL` entrega la URL del `.mpd` **y la cookie
 * `playback_token`**, que es lo que después autoriza la licencia Widevine. Revisar el entitlement
 * antes de pedir la URL es lo que convierte un geobloqueo en un mensaje claro en vez de un fallo
 * del reproductor diez segundos más tarde.
 *
 * El vivo son solo dos: el `assetId` ya viene con el canal (ver `DituCatalogo.canales`).
 */
internal class DituResolve(private val cliente: DituClienteLike) {

    suspend fun vod(ref: DituRef): GatewayPlayable {
        val (contentId, assetId) = when {
            ref.contentType == "GROUP_OF_BUNDLES" -> primerCapituloDeGrupo(ref.contentId)
            ref.esSerie -> primerCapitulo(ref.contentId)
            else -> {
                val detalle = cliente.get("CONTENT/DETAIL/${ref.contentType}/${ref.contentId}")
                val contenedor = DituCatalogo.contenedoresDe(detalle).firstOrNull()
                    ?: throw DituException("Caracol no devolvió el detalle de ${ref.contentId}")
                val asset = DituCatalogo.assetMaster(contenedor)
                    ?: throw DituException("Caracol no tiene un asset reproducible para ${ref.contentId}")
                ref.contentId to asset
            }
        }

        revisarEntitlement("CONTENT/USERDATA/VOD/$contentId")
        return playableDe("CONTENT/VIDEOURL/VOD/$contentId/$assetId", contentId)
    }

    suspend fun vivo(canal: DituCanal): GatewayPlayable {
        revisarEntitlement("CONTENT/USERDATA/LIVE/${canal.channelId}")
        return playableDe("CONTENT/VIDEOURL/LIVE/${canal.channelId}/${canal.assetId}", canal.nombre)
    }

    /**
     * Un GROUP_OF_BUNDLES no es reproducible en sí: lo que se abre es el primer capítulo
     * reproducible de su primer bundle hijo que tenga capítulos.
     */
    private suspend fun primerCapituloDeGrupo(groupId: String): Pair<String, Int> {
        val hijos = cliente.get(
            DituCatalogo.TRAY,
            mapOf("filter_parentId" to groupId, "filter_contentType" to "BUNDLE"),
        )
        val ids = DituCatalogo.contenedoresDe(hijos).mapNotNull { it.optString("id").takeIf { s -> s.isNotBlank() } }

        for (bundleId in ids) {
            val resultado = runCatching { primerCapitulo(bundleId) }.getOrNull()
            if (resultado != null) return resultado
        }

        throw DituException("Ningún capítulo de la serie está disponible para reproducir")
    }

    /** Un BUNDLE no es reproducible en sí: lo que se abre es su primer capítulo con asset. */
    private suspend fun primerCapitulo(bundleId: String): Pair<String, Int> {
        val detalle = cliente.get("CONTENT/DETAIL/BUNDLE/$bundleId")
        val externo = DituCatalogo.contenedoresDe(detalle).firstOrNull()
            ?: throw DituException("Caracol no devolvió el detalle de $bundleId")
        val crudos = externo.optJSONArray("containers")
        for (i in 0 until (crudos?.length() ?: 0)) {
            val ep = crudos!!.optJSONObject(i) ?: continue
            val id = ep.optString("id").takeIf { it.isNotBlank() } ?: continue
            val asset = DituCatalogo.assetMaster(ep) ?: continue
            return id to asset
        }
        throw DituException("Ningún capítulo de $bundleId se puede reproducir")
    }

    private suspend fun revisarEntitlement(path: String) {
        val datos: JSONObject = cliente.get(path)
        DituEntitlement.bloqueo(datos)?.let { throw DituException("Caracol: $it") }
    }

    private suspend fun playableDe(path: String, queEs: String): GatewayPlayable {
        val r = cliente.getConToken(path)
        val src = r.json.optJSONObject("resultObj")?.optString("src").orEmpty()
        if (src.isBlank()) throw DituException("Caracol no devolvió una URL de video para $queEs")
        return GatewayPlayable(
            kind = FUENTE,
            url = src,
            mime = "application/dash+xml",
            drmLicenseUrl = DituCliente.LICENCIA,
            // Sin token NO se falla acá: si la licencia después responde 500, ese error dice más
            // que uno inventado antes de intentarlo.
            drmLicenseHeaders = if (r.playbackToken.isBlank()) {
                emptyMap()
            } else {
                mapOf("Cookie" to "${DituCliente.COOKIE_TOKEN}=${r.playbackToken}")
            },
        )
    }

    internal companion object {
        const val FUENTE = "ditu"
    }
}
