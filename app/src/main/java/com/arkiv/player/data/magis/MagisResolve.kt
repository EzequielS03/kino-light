package com.arkiv.player.data.magis

import com.arkiv.player.BuildConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class MagisSubtitulo(val lang: String, val url: String, val formato: String)

/**
 * Lo que hace falta para reproducir un título de Magis. Es un espejo de `GatewayPlayable` a
 * propósito: quien cablea esto solo tiene que copiar campos, sin decidir nada.
 */
internal data class MagisPlayable(
    val url: String,
    val headers: Map<String, String>,
    val mime: String,
    /** El contenedor tal como lo NOMBRA el portal ("ts", "mp4"). No se deduce de la extensión de
     *  [url]: esa la arma este código colapsando a `.mp4` todo lo que el portal no llame `ts`. */
    val container: String,
    val videoCodec: String,
    val durationMs: Long,
    val subtitulos: List<MagisSubtitulo>,
)

/**
 * Resolución de VOD: del `contentId` a la URL del CDN con sus cabeceras. Puerto de
 * `MagisAdapter.resolve` (`adapters/magis/adapter.py`).
 *
 * Son dos llamadas al portal: `v10/startPlayVOD` (la pista y su licencia, por título) y
 * `v14/getSlbInfo` (el CDN y el `Content-Auth`, por SESIÓN — el mismo para todos los títulos, por
 * eso se cachea acá).
 */
internal class MagisResolve(
    private val portal: MagisPortalClientLike,
    private val session: MagisSession,
    private val appId: String = BuildConfig.IPTV_APP_ID,
    private val apkVersion: String = BuildConfig.IPTV_APK_VERSION,
    private val ahoraMs: () -> Long = { System.currentTimeMillis() },
) {

    private val candadoSlb = Mutex()
    private var slb: JSONObject? = null
    private var slbVenceMs = 0L
    private var slbDeToken = ""

    /**
     * [contentId] tiene que ser el de la PISTA reproducible: el `contentId` de una serie no se
     * reproduce (`play_vod` sobre él devuelve `节目不存在`), hay que pasarle el del capítulo y la
     * serie en [seriesContentId].
     */
    suspend fun resolveVod(
        contentId: String,
        seriesContentId: String? = null,
    ): MagisResult<MagisPlayable> {
        val sesion = session.ensureSession()
        if (sesion !is MagisResult.Ok) return sesion.comoError()

        val play = session.conSesionValida {
            portal.call(
                path = "v10/startPlayVOD",
                bean = mapOf(
                    "contentId" to contentId,
                    "seriesContentId" to seriesContentId.orEmpty(),
                    "startTime" to 0,
                    "type" to "1",
                    "columnId" to 0,
                    "authType" to "",
                ),
                userId = session.userId,
                userToken = session.userToken,
            )
        }
        val playJson = play.dato() ?: return play.comoError()

        val media = mejorMedia(playJson)
            ?: return MagisResult.PortalError("sin_media", "magis devolvió sin media reproducible")
        val license = media.optJSONArray("licenseList")?.optJSONObject(0)?.optString("license")
            ?.takeIf { it.isNotBlank() }
            ?: return MagisResult.PortalError("sin_license", "magis devolvió sin licenseList")

        val slbActual = slbDeSesion()
        val slbJson = slbActual.dato() ?: return slbActual.comoError()
        val cdn = cdnDeVod(slbJson)
            ?: return MagisResult.PortalError("sin_cdn_vod", "magis no expuso CDN de vod con token libre")

        // `container` es lo que DICE el portal y viaja crudo hasta el demuxer; `ext` es la clave del
        // objeto en el CDN, que solo existe en dos sabores. Pedir `.mp4` cuando el portal dijo otra
        // cosa es lo único que se puede hacer, pero no convierte el archivo en un mp4.
        val container = media.optString("videoFormat").lowercase()
        val ext = if (container == "ts") "ts" else "mp4"

        return MagisResult.Ok(
            MagisPlayable(
                url = "${cdn.base}/vod/${media.optString("contentId")}_media.$ext",
                headers = mapOf(
                    "Content-Auth" to cdn.auth,
                    "Content-License" to license,
                    "User-Agent" to UA_CDN,
                    "App" to appId,
                    "App-Version" to apkVersion,
                ),
                mime = if (ext == "mp4") "video/mp4" else "video/mp2t",
                container = container,
                videoCodec = media.optString("encodeFormat").lowercase(),
                durationMs = duracionMsDelPortal(media.opt("duration")),
                subtitulos = subtitulos(playJson),
            ),
        )
    }

    /**
     * La pista con más probabilidades de reproducirse en cualquier equipo: **h264 primero y recién
     * después mp4**, en ese orden de precedencia y no las dos cosas a la vez. Exigir ambas y caer al
     * primer candidato entregaba h265 en los títulos servidos solo en TS (que son casi todos), y un
     * HEVC que el decodificador por hardware no arranca deja la pantalla en negro.
     *
     * Estable: entre dos igual de buenas gana la que el portal ofreció primero.
     */
    private fun mejorMedia(play: JSONObject): JSONObject? {
        val episodio = play.optJSONArray("episodeList")?.optJSONObject(0) ?: return null
        val candidatos = mutableListOf<JSONObject>()
        episodio.optJSONArray("totalMovieList")?.forEachObjeto { tm ->
            tm.optJSONArray("movieList")?.forEachObjeto { candidatos.add(it) }
        }
        return candidatos.minByOrNull { m ->
            val codec = if (m.optString("encodeFormat").lowercase() == "h264") 0 else 2
            val contenedor = if (m.optString("videoFormat").lowercase() == "mp4") 0 else 1
            codec + contenedor
        }
    }

    /** `subtitleList[].file[0].url`. El portal a veces lista un idioma que no subió: se descarta. */
    private fun subtitulos(play: JSONObject): List<MagisSubtitulo> {
        val episodio = play.optJSONArray("episodeList")?.optJSONObject(0) ?: return emptyList()
        val salida = mutableListOf<MagisSubtitulo>()
        episodio.optJSONArray("subtitleList")?.forEachObjeto { sub ->
            val archivo = sub.optJSONArray("file")?.optJSONObject(0) ?: return@forEachObjeto
            val url = archivo.optString("url").takeIf { it.isNotBlank() } ?: return@forEachObjeto
            salida.add(
                MagisSubtitulo(
                    lang = sub.optString("language"),
                    url = url,
                    formato = archivo.optString("fileType").ifBlank { "srt" },
                ),
            )
        }
        return salida
    }

    private data class CdnVod(val base: String, val auth: String)

    /** El CDN de VOD y el `Content-Auth` del tier libre. */
    private fun cdnDeVod(slb: JSONObject): CdnVod? {
        slb.optJSONArray("cdn_list")?.forEachObjeto { cdn ->
            if (cdn.optString("tag") != "vod") return@forEachObjeto
            cdn.optJSONArray("url_list")?.forEachObjeto { u ->
                val url = u.optString("url")
                val sirve = esCfl(url) || u.optString("sign_type") == "cfl"
                if (sirve && u.optString("tag") == "free") {
                    return CdnVod(base = conEsquema(cdn.optString("main_addr")), auth = url)
                }
            }
        }
        return null
    }

    /**
     * `getSlbInfo` de esta sesión, pedido UNA vez mientras siga sirviendo: no recibe el `contentId`
     * porque el CDN y el `Content-Auth` son los mismos para todos los títulos. Pedirlo en cada
     * resolución es un viaje al portal (con su ritmo mínimo) que el usuario paga mirando el spinner.
     */
    private suspend fun slbDeSesion(): MagisResult<JSONObject> = candadoSlb.withLock {
        val vigente = slb
        if (vigente != null && ahoraMs() < slbVenceMs && slbDeToken == session.userToken) {
            return@withLock MagisResult.Ok(vigente)
        }
        val r = session.conSesionValida {
            portal.call(
                path = "v14/getSlbInfo",
                bean = beanDeSlb(apkVersion),
                userId = session.userId,
                userToken = session.userToken,
            )
        }
        val fresco = r.dato() ?: return@withLock r
        val vida = vidaDelSlb(fresco)
        if (vida > 0) {
            slb = fresco
            slbVenceMs = ahoraMs() + vida * 1000L
            slbDeToken = session.userToken
        } else {
            // Un slb que ya no sirve NO se guarda: guardarlo dejaría la sesión rota y callada hasta
            // que venciera. Se devuelve igual, que es lo único que hay.
            slb = null
        }
        MagisResult.Ok(fresco)
    }

    /**
     * Segundos que se puede guardar este `getSlbInfo`. Manda el que venza antes: el `invalidTime`
     * que declara el portal (medido: 14400 = 4 h) o el `expired=<unix>` que el propio `Content-Auth`
     * lleva en su querystring. Un caché que viva más que el token entrega un auth muerto y el fallo
     * pasa adentro del reproductor, sin error visible en ninguna parte.
     */
    private fun vidaDelSlb(slb: JSONObject): Long {
        val declarada = slb.optString("invalidTime").toLongOrNull()?.takeIf { it > 0 } ?: TTL_SLB_S
        val auth = cdnDeVod(slb)?.auth ?: return 0
        val expira = EXPIRED.find(auth)?.groupValues?.get(1)?.toLongOrNull()
            ?: return declarada
        return minOf(declarada, expira - ahoraMs() / 1000 - MARGEN_AUTH_S)
    }

    private companion object {
        const val UA_CDN = "Ranger/4.9.4-17294ac0"

        /** Conservador, para cuando el portal no declara `invalidTime`. */
        const val TTL_SLB_S = 300L
        const val MARGEN_AUTH_S = 300L
        val EXPIRED = Regex("""expired=(\d+)""")

    }
}

/**
 * Duración en ms de lo que manda el portal: llega como "HH:MM:SS", "MM:SS" o los segundos pelados
 * (número o texto). Cualquier otra cosa vale 0 — para pintar la barra es mejor no tener duración
 * (la app sondea los PCR) que tener una inventada, que además manda el seek a cualquier parte.
 */
internal fun duracionMsDelPortal(crudo: Any?): Long {
    if (crudo == null || crudo == JSONObject.NULL) return 0
    if (crudo is Number) return (crudo.toDouble() * 1000).toLong()
    val texto = crudo.toString().trim()
    if (texto.isEmpty()) return 0
    val partes = texto.split(":")
    if (partes.size > 3 || partes.any { it.isBlank() || !it.all(Char::isDigit) }) return 0
    val segundos = partes.fold(0L) { acc, p -> acc * 60 + p.toLong() }
    return segundos * 1000
}
