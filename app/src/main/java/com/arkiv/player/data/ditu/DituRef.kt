package com.arkiv.player.data.ditu

import org.json.JSONObject
import java.util.Base64

/**
 * Qué hay que reproducir de Caracol, en una cadena que la app guarda en su base.
 *
 * Mismo criterio que [com.arkiv.player.data.magis.MagisRef]: el gateway acuñaba un ref firmado que
 * vencía, y sin servidor no hay a quién pedirle uno nuevo. Tampoco hace falta — para resolver,
 * Caracol solo necesita el `contentId` y el `contentType`, que no vencen. Y a diferencia de Magis,
 * acá eso importa de verdad: los ids de Caracol son estables, así que un capítulo guardado en la
 * biblioteca sigue reproduciendo el mes que viene.
 *
 * [contentType] es lo que la API llama al contenido: `VOD` (película o capítulo suelto), `BUNDLE`
 * (una temporada) o `GROUP_OF_BUNDLES` (una serie con varias temporadas).
 */
internal data class DituRef(
    val contentId: String,
    val contentType: String = "VOD",
) {
    val esSerie: Boolean get() = contentType in SERIES

    /** `ditu1:<contentType>:<contentId>` — el contentId va último para que no importe si algún
     *  día trae un `:` adentro. */
    fun codificar(): String = "$PREFIJO:$contentType:$contentId"

    internal companion object {
        const val PREFIJO = "ditu1"

        /** Los tipos que hay que listar antes de poder reproducir. */
        val SERIES = setOf("BUNDLE", "GROUP_OF_BUNDLES")

        /** Lee un ref propio o uno viejo del gateway. `null` si no es de Ditu o no se entiende. */
        fun decodificar(ref: String): DituRef? {
            if (ref.isBlank()) return null
            if (ref.startsWith("$PREFIJO:")) {
                val partes = ref.split(":", limit = 3)
                if (partes.size < 3) return null
                val contentId = partes[2].takeIf { it.isNotBlank() } ?: return null
                return DituRef(contentId = contentId, contentType = partes[1].ifBlank { "VOD" })
            }
            return deRefDelGateway(ref)
        }

        private fun deRefDelGateway(ref: String): DituRef? {
            val datos = ref.substringBefore('.').takeIf { it.isNotBlank() && it != ref } ?: return null
            val json = runCatching {
                // `java.util.Base64` y no `android.util.Base64`: el de Android es un stub que
                // devuelve null en los tests de JVM, y este es justo el camino que solo corre una
                // vez, en silencio, para no perder lo que ya está guardado.
                JSONObject(String(Base64.getUrlDecoder().decode(datos), Charsets.UTF_8))
            }.getOrNull() ?: return null
            if (json.optString("s") != "ditu") return null
            val p = json.optJSONObject("p") ?: return null
            val contentId = p.optString("content_id").takeIf { it.isNotBlank() } ?: return null
            return DituRef(
                contentId = contentId,
                contentType = p.optString("content_type").ifBlank { "VOD" },
            )
        }
    }
}
