package com.arkiv.player.data.magis

import com.arkiv.player.BuildConfig
import com.arkiv.player.data.gateway.CdnDeCanal
import com.arkiv.player.data.gateway.LiveSession
import org.json.JSONObject

/**
 * Canal en vivo directo del portal. Devuelve la misma [LiveSession] que hasta ahora armaba
 * `LiveApi.resolver` contra el gateway, así que `LiveHlsProxy` no cambia: sigue pidiéndole
 * `http://<cflHost>/live/<playCode>.m3u8` al CDN y firmando cada segmento en el aparato
 * ([com.arkiv.player.playback.FirmaLocal]).
 *
 * Son dos llamadas al portal y NO se cachean: el `main_addr` rota en cada respuesta, y un host
 * viejo contesta 403.
 */
internal class MagisLive(
    private val portal: MagisPortalClientLike,
    private val session: MagisSession,
    private val apkVersion: String = BuildConfig.IPTV_APK_VERSION,
    private val ahoraMs: () -> Long = { System.currentTimeMillis() },
) {

    suspend fun resolveChannel(channelCode: String): MagisResult<LiveSession> {
        // El vivo exige cuenta de verdad: con sesión anónima el portal contesta `aaa100028`
        // ("未登录！"). Se corta acá para no gastar dos llamadas y para poder decir por qué.
        if (!session.hasAccountLinked) {
            return MagisResult.PortalError(
                SIN_CUENTA,
                "el canal en vivo exige una cuenta de Magis vinculada: la sesión anónima no alcanza",
            )
        }
        val sesion = session.ensureSession()
        if (sesion !is MagisResult.Ok) return sesion.comoError()

        val play = session.conSesionValida {
            portal.call(
                path = "v4/startPlayLive",
                bean = mapOf("channelCode" to channelCode, "columnId" to 0, "type" to "1"),
                userId = session.userId,
                userToken = session.userToken,
            )
        }
        val playJson = play.dato() ?: return play.comoError()
        val señal = señalDe(playJson)
            ?: return MagisResult.PortalError(
                SIN_DIRECCIONES,
                "el portal no dio direcciones para $channelCode",
            )

        val slb = session.conSesionValida {
            portal.call(
                path = "v14/getSlbInfo",
                // El código del CANAL, no el playCode: el portal devuelve los hosts de esa señal.
                bean = beanDeSlb(apkVersion, liveCodes = listOf(channelCode)),
                userId = session.userId,
                userToken = session.userToken,
            )
        }
        val slbJson = slb.dato() ?: return slb.comoError()

        val cdns = cdnsDeVivo(slbJson)
        if (cdns.isEmpty()) {
            return MagisResult.PortalError(
                SIN_CDN,
                "no hay entrada CDN cfl de vivo para $channelCode",
            )
        }

        val sesionDeCanal = LiveSession(
            cflHost = cdns.first().cflHost,
            authBase = cdns.first().authBase,
            license = señal.license,
            channel = channelCode,
            expiresAt = ahoraMs() / 1000 + vigenciaDe(slbJson),
            // Cómo se llama la señal EN EL CDN, que no siempre es el código del canal (medido:
            // `cyx-RCNHD` se sirve como `cyx-2EF7E10E40C1ac19D6A9F3ED4CD2`). Pedirle al CDN el
            // código del canal es pedirle una señal distinta de la que autoriza la licencia: 401.
            playCode = señal.playCode.ifBlank { channelCode },
            cdns = cdns,
        )

        // Lo que sigue es la misma validación que hacía `LiveApi.resolver` sobre la respuesta del
        // gateway, y por el mismo motivo: `LiveHlsProxy` usa estos campos tal cual contra el CDN
        // real, así que un vacío acá reaparece como un 401/403 opaco, lejos de donde se originó.
        if (sesionDeCanal.license.isBlank()) {
            return MagisResult.PortalError(SIN_LICENSE, "el portal dio $channelCode sin licencia")
        }
        if (sesionDeCanal.token.isBlank()) {
            return MagisResult.PortalError(
                SIN_TOKEN,
                "el authBase de $channelCode no trae token=<32 hex>",
            )
        }
        return MagisResult.Ok(sesionDeCanal)
    }

    /**
     * Como [resolveChannel] pero lanzando, que es lo que espera quien abre un canal (antes lo
     * lanzaba `LiveApi.resolver`). El mensaje lleva el motivo del portal: es lo que se ve cuando un
     * canal no abre, y "no se pudo abrir" a secas no deja diagnosticar nada.
     */
    suspend fun resolverOLanzar(channelCode: String): LiveSession {
        val r = resolveChannel(channelCode)
        return r.dato() ?: throw com.arkiv.player.data.gateway.GatewayException(
            when (r) {
                is MagisResult.PortalError -> "vivo: ${r.codigo}${r.msg?.let { " ($it)" }.orEmpty()}"
                is MagisResult.RedError -> "vivo: no se pudo hablar con el portal (${r.causa.message})"
                is MagisResult.Ok -> "vivo: el portal no dio sesión"
            },
        )
    }

    private data class Señal(val playCode: String, val license: String)

    /**
     * El `playCode` y la licencia salen de LA MISMA entrada, nunca cruzados: la licencia autoriza
     * UNA señal, y emparejarla con el playCode de otra es justo el par que el CDN rechaza. Es lo
     * que hace la app original (decompilada, `sources/h8/v3.java`): descarta las direcciones sin
     * playCode o sin licencia y usa el par de la que quedó.
     *
     * Se prefiere la primera COMPLETA; si ninguna lo está, vale la primera con licencia y sin
     * playCode — hay canales cuyo playCode el portal no manda y que andan con el código del canal,
     * así que arreglar unos no puede romper esos.
     */
    private fun señalDe(play: JSONObject): Señal? {
        var primeraLicencia: String? = null
        play.optJSONArray("liveAddressList")?.forEachObjeto { a ->
            val license = a.optString("license")
            val playCode = a.optString("playCode")
            if (primeraLicencia == null) primeraLicencia = license
            if (playCode.isNotBlank() && license.isNotBlank()) {
                return Señal(playCode = playCode, license = license)
            }
        }
        return primeraLicencia?.let { Señal(playCode = "", license = it) }
    }

    /**
     * TODOS los CDN de vivo servibles, no el primero: medido el 2026-08-14 el portal devuelve tres
     * y se usaba solo uno; ese día el CDN contestó 401 y el canal se murió teniendo otro host en la
     * MISMA respuesta. Cada uno con SU `authBase`, porque el token viaja adentro de esa url y
     * firmar con el de uno contra el host de otro es el mismo modo de falla.
     */
    private fun cdnsDeVivo(slb: JSONObject): List<CdnDeCanal> {
        val salida = mutableListOf<CdnDeCanal>()
        slb.optJSONArray("cdn_list")?.forEachObjeto { cdn ->
            if (cdn.optString("tag") != "live") return@forEachObjeto
            cdn.optJSONArray("url_list")?.forEachObjeto { u ->
                val url = u.optString("url")
                if (!esCfl(url) && u.optString("sign_type") != "cfl") return@forEachObjeto
                val host = hostPelado(cdn.optString("main_addr"))
                if (host.isNotBlank()) salida.add(CdnDeCanal(cflHost = host, authBase = url))
            }
        }
        return salida
    }

    /**
     * Cuánto vale esta info de CDN, según EL PORTAL: `invalidTime` (medido: 14400 = 4 h). Sin el
     * campo se cae al valor conservador — re-resolver de más cuesta unos segundos, servir un host
     * vencido corta la reproducción.
     */
    private fun vigenciaDe(slb: JSONObject): Long =
        slb.optString("invalidTime").toLongOrNull()?.takeIf { it > 0 } ?: TTL_CANAL_S

    internal companion object {
        const val TTL_CANAL_S = 300L

        const val SIN_CUENTA = "live_sin_cuenta"
        const val SIN_DIRECCIONES = "live_sin_direcciones"
        const val SIN_CDN = "live_sin_cdn_cfl"
        const val SIN_LICENSE = "live_sin_license"
        const val SIN_TOKEN = "live_sin_token_cfl"
    }
}
