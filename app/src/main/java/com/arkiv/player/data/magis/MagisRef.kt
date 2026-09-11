package com.arkiv.player.data.magis

import org.json.JSONObject
import java.util.Base64

/**
 * What to play, as a string the app stores in its local database (`torrentData`). Until Task 5
 * that column traveled between devices through cloud sync; without that sync, this string stays
 * only on the device that saved it.
 *
 * Reemplaza al `ref` que acuñaba el gateway (`base64url(json).hmac`, **con 24 h de vencimiento**):
 * sin servidor no hay a quién pedirle uno nuevo, y tampoco hace falta: lo único que el portal
 * necesita para resolver es el `contentId`, que no vence. Un ref local además arregla de paso algo
 * que estaba roto — un ítem guardado en la biblioteca hace más de un día llevaba un ref muerto.
 *
 * [tipoPrograma] es lo que el portal llama al contenido ("movie", "teleplay", "variety"…), no el
 * "movie"/"tv" de TMDB: es el que decide si hay que listar capítulos antes de reproducir.
 */
internal data class MagisRef(
    val contentId: String,
    val tipoPrograma: String = "movie",
    val episodio: Int = 0,
) {
    val esSerie: Boolean get() = tipoPrograma in SERIES

    /** `magis1:<tipo>:<episodio>:<contentId>` — el contentId va último para que no importe si
     *  algún día trae un `:` adentro. */
    fun codificar(): String = "$PREFIJO:$tipoPrograma:$episodio:$contentId"

    internal companion object {
        const val PREFIJO = "magis1"

        /** Los tipos que el portal sirve por capítulos. Una sola definición: la que ya consumen
         *  las pantallas (`MAGIS_SERIES`). */
        val SERIES = com.arkiv.player.data.gateway.MAGIS_SERIES

        /**
         * Lee un ref propio o uno viejo del gateway. `null` si no es de Magis o no se entiende.
         *
         * El ref del gateway era opaco para la app **por contrato**, no por criptografía: es
         * `base64url(json).hmac`, y el json se lee sin la llave. Eso es lo que deja migrar la
         * biblioteca que ya está guardada en vez de pedirle a la persona que la agregue de nuevo.
         * La firma no se valida (no hay con qué, y tampoco importa: lo que sale de acá no autoriza
         * nada, solo dice qué título pedirle al portal) y el vencimiento se ignora a propósito.
         */
        fun decodificar(ref: String): MagisRef? {
            if (ref.isBlank()) return null
            if (ref.startsWith("$PREFIJO:")) {
                val partes = ref.split(":", limit = 4)
                if (partes.size < 4) return null
                val contentId = partes[3].takeIf { it.isNotBlank() } ?: return null
                return MagisRef(
                    contentId = contentId,
                    tipoPrograma = partes[1].ifBlank { "movie" },
                    episodio = partes[2].toIntOrNull() ?: 0,
                )
            }
            return deRefDelGateway(ref)
        }

        private fun deRefDelGateway(ref: String): MagisRef? {
            val datos = ref.substringBefore('.').takeIf { it.isNotBlank() && it != ref } ?: return null
            val json = runCatching {
                // `java.util.Base64` y no `android.util.Base64`: el de Android es un stub que
                // devuelve null en los tests de JVM, así que el camino de migración no se podría
                // probar (y este es justo el camino que solo se ejecuta una vez, en silencio).
                JSONObject(String(Base64.getUrlDecoder().decode(datos), Charsets.UTF_8))
            }.getOrNull() ?: return null
            if (json.optString("s") != "magis") return null
            val p = json.optJSONObject("p") ?: return null
            val contentId = p.optString("content_id").takeIf { it.isNotBlank() } ?: return null
            return MagisRef(
                contentId = contentId,
                tipoPrograma = p.optString("program_type").ifBlank { "movie" },
                episodio = p.optInt("episode", 0),
            )
        }
    }
}
