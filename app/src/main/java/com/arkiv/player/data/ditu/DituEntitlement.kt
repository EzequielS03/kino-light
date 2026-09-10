package com.arkiv.player.data.ditu

import org.json.JSONObject

/**
 * Por qué Caracol no te deja ver algo.
 *
 * La API responde con siete banderas distintas y cada una manda a la persona a un lado diferente:
 * un geobloqueo se arregla con VPN o no se arregla, una suscripción se compra, un control parental
 * se desactiva en el aparato. Colapsarlas a "no se pudo reproducir" convierte cualquiera de las
 * siete en un bug aparente de la app.
 *
 * El orden importa: con varias activas gana la primera, que es la más informativa.
 */
internal object DituEntitlement {

    private val BLOQUEOS = linkedMapOf(
        "isGeoBlocked" to "solo disponible en Colombia",
        "isChannelNotSubscribed" to "requiere suscripción",
        "isPCBlocked" to "control parental activo",
        "isContentOOHBlocked" to "contenido OOH bloqueado",
        "isGeofencedBlocked" to "geofence bloqueado",
        "isSportBlackoutBlocked" to "deportes en blackout",
        "isPlatformBlacklisted" to "plataforma no permitida",
    )

    /**
     * El mensaje del primer bloqueo activo, o `null` si no hay ninguno.
     *
     * Una respuesta que no tiene la forma esperada devuelve `null` a propósito: no saber si hay
     * bloqueo no es lo mismo que haberlo, y afirmarlo mandaría a buscar el problema donde no está.
     */
    fun bloqueo(userData: JSONObject): String? {
        val contenedores = userData.optJSONObject("resultObj")?.optJSONArray("containers") ?: return null
        val ent = contenedores.optJSONObject(0)?.optJSONObject("entitlement") ?: return null
        for ((flag, mensaje) in BLOQUEOS) {
            // `opt` y no `optBoolean`: optBoolean("x", false) devuelve true para el string "true",
            // y un string no es lo que la API manda cuando de verdad bloquea.
            if (ent.opt(flag) == true) return mensaje
        }
        return null
    }
}
