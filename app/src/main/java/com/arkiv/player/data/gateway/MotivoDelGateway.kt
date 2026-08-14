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
        // `detail` puede ser un string (lo normal en FastAPI) o un objeto (así lo manda la capa de
        // identidad, con `codigo` y `mensaje`). Los dos sirven; se muestra lo que haya.
        if (o.has("detail")) o.get("detail").toString() else limpio
    }.getOrDefault(limpio)
    return if (texto.length <= MOTIVO_MAX) texto else texto.take(MOTIVO_MAX) + "…"
}
