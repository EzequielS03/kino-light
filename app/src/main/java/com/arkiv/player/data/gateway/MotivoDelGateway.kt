package com.arkiv.player.data.gateway

import org.json.JSONObject

/** Tope del motivo, para que un cuerpo raro no inunde el log ni la excepción. */
const val MOTIVO_MAX = 300

/**
 * El PORQUÉ que viene en el cuerpo de una respuesta de error del gateway.
 *
 * Existe por un rato perdido el 2026-08-14: ningún canal de TV en vivo abría y el log del Fire TV
 * decía, para todos, `live: el gateway respondio 502`. Nada más. El motivo real —el portal
 * contestando `aaa100028 / 未登录!`, o sea que el directo exige cuenta de Magis vinculada mientras
 * que el VOD anda con la anónima— viajaba en el cuerpo, que se leía y se descartaba. Hubo que
 * entrar al contenedor del servidor y reproducir la llamada a mano para enterarse de algo que ya
 * estaba en la respuesta que la app tenía en la mano.
 *
 * Nunca lanza: si el cuerpo no se puede interpretar se devuelve tal cual, porque es lo único que
 * hay para saber qué pasó.
 */
fun motivoDelGateway(cuerpo: String): String {
    val limpio = cuerpo.trim()
    if (limpio.isEmpty()) return ""
    val texto = runCatching {
        val o = JSONObject(limpio)
        // `detail` puede ser un string (lo normal en FastAPI) o un objeto con `codigo` y
        // `mensaje` (la capa de identidad, y desde el 2026-08-14 también los fallos del portal en
        // vivo, que pasaron de 502 a 409 justamente para que el cuerpo llegue).
        //
        // De un objeto se muestra `mensaje` y no el objeto entero: esto termina EN PANTALLA, y
        // `{"codigo":"magis_sesion_vencida","mensaje":"Tu sesión…"}` es peor que el "502" que se
        // mostraba antes. Sin `mensaje` se cae al objeto crudo — feo, pero es lo único que hay, y
        // callarse deja a la persona sin ninguna pista.
        val detalle = if (o.has("detail")) o.get("detail") else null
        when {
            detalle is JSONObject ->
                detalle.optString("mensaje").ifBlank { detalle.toString() }
            detalle != null -> detalle.toString()
            else -> limpio
        }
    }.getOrDefault(limpio)
    return if (texto.length <= MOTIVO_MAX) texto else texto.take(MOTIVO_MAX) + "…"
}
