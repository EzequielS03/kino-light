package com.arkiv.player.data.magis

import org.json.JSONArray
import org.json.JSONObject

/**
 * `v14/getSlbInfo` es la misma llamada para VOD y para vivo: devuelve los CDN y el token del tier
 * libre. Lo único que cambia es [liveCodes] — para vivo va el código del canal que se está
 * resolviendo, porque el portal devuelve los hosts de ESA señal.
 */
internal fun beanDeSlb(
    apkVersion: String,
    liveCodes: List<String> = listOf("masnew_live"),
): Map<String, Any?> = mapOf(
    "hasPay" to "0",
    "userIdentity" to "1",
    "type" to "merge",
    "appVer" to apkVersion,
    "lang" to "es",
    "encMediaSupported" to 1,
    // JSONArray explícito: el `JSONObject(Map)` de Android NO convierte una `List` de Kotlin, la
    // serializa como el texto "[masnew_live]" y el portal recibe basura.
    "liveCodeList" to JSONArray(liveCodes),
    "appParams" to "",
    "reserve1" to "02:00:00:00:00:00",
    "pipFlag" to "0",
)

/**
 * `sign_type=cfl` exacto, no un prefijo parecido como `cflx`. El campo `url` de `getSlbInfo` NO es
 * una URL: es un querystring suelto, sin esquema ni `?` (ej. `cdn_type=1&sign_type=cfl&token=ABC`),
 * así que parsearlo como URL no encuentra nunca el parámetro y ninguna entrada matchea.
 */
internal fun esCfl(url: String): Boolean = url.substringAfterLast('?')
    .split('&')
    .any { it.trim() == "sign_type=cfl" }

/**
 * `main_addr` con esquema, para armar una URL completa (VOD). Llega con esquema en producción —y a
 * veces con path, que se conserva—, pero sin esto un host pelado armaría una URL que el reproductor
 * no abre, y el fallo aparecería lejos de acá.
 */
internal fun conEsquema(mainAddr: String): String {
    val limpio = mainAddr.trimEnd('/')
    return if (limpio.startsWith("http://") || limpio.startsWith("https://")) limpio
    else "https://$limpio"
}

/**
 * Solo el host de `main_addr`, sin esquema ni path: es lo que el proxy de HLS espera, porque arma
 * `http://<host>/live/<playCode>.m3u8` por su cuenta.
 */
internal fun hostPelado(mainAddr: String): String = mainAddr
    .removePrefix("https://")
    .removePrefix("http://")
    .substringBefore('/')

/** Recorrer un `JSONArray` de objetos sin escribir el índice a mano en cada lugar. */
internal inline fun JSONArray.forEachObjeto(accion: (JSONObject) -> Unit) {
    for (i in 0 until length()) optJSONObject(i)?.let(accion)
}
