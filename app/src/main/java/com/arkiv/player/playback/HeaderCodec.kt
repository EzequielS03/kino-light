package com.arkiv.player.playback

import org.json.JSONObject
import java.util.Base64

/**
 * Empaqueta un mapa de headers dentro de una URL.
 *
 * Hace falta porque libVLC solo sabe mandar `Referer` y `User-Agent`: cualquier otro header
 * (los `Content-Auth` / `Content-License` de magis) tiene que viajar hasta el proxy local por
 * el único canal que VLC respeta, que es la propia URL.
 *
 * Base64 URL-safe sin padding: el valor va como parámetro de query y no debe traer `+`, `/` ni `=`.
 * Se usa `java.util.Base64` (API 26+, y el minSdk es 26) y no `android.util.Base64` porque este
 * ultimo es un stub en los tests JVM y devuelve null.
 */
object HeaderCodec {

    fun encode(headers: Map<String, String>): String {
        if (headers.isEmpty()) return ""
        val json = JSONObject().apply { headers.forEach { (k, v) -> put(k, v) } }.toString()
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    /** Nunca lanza: una URL manipulada o vieja simplemente no aporta headers. */
    fun decode(blob: String): Map<String, String> {
        if (blob.isBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(String(Base64.getUrlDecoder().decode(blob), Charsets.UTF_8))
            json.keys().asSequence().associateWith { json.optString(it) }
        }.getOrDefault(emptyMap())
    }
}
