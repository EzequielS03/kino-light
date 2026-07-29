package com.arkiv.player.pairing

import java.net.URLDecoder
import java.net.URLEncoder

/** Contenido del QR de pareo. */
data class QrPayload(val dbUrl: String, val code: String)

/** Codifica/decodifica el QR como `arkiv://pair?u=<urlEncoded>&c=<code>`. */
object QrPayloadCodec {
    fun encode(p: QrPayload): String {
        val u = URLEncoder.encode(p.dbUrl, "UTF-8")
        val c = URLEncoder.encode(p.code, "UTF-8")
        return "${PairingConfig.QR_SCHEME}://${PairingConfig.QR_HOST}?u=$u&c=$c"
    }

    fun decode(s: String): QrPayload? = runCatching {
        val prefix = "${PairingConfig.QR_SCHEME}://${PairingConfig.QR_HOST}?"
        if (!s.startsWith(prefix)) return null
        val query = s.substring(prefix.length)
        val params = query.split('&').mapNotNull {
            val i = it.indexOf('='); if (i < 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
        val u = params["u"]?.let { URLDecoder.decode(it, "UTF-8") } ?: return null
        val c = params["c"]?.let { URLDecoder.decode(it, "UTF-8") } ?: return null
        if (u.isBlank() || c.isBlank()) return null
        QrPayload(dbUrl = u, code = c)
    }.getOrNull()
}
