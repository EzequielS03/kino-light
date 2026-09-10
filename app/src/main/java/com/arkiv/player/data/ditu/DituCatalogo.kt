package com.arkiv.player.data.ditu

import org.json.JSONArray
import org.json.JSONObject

/** Un título del catálogo de Caracol: serie (`BUNDLE`/`GROUP_OF_BUNDLES`) o película (`VOD`). */
internal data class DituItem(
    val contentId: String,
    val titulo: String,
    val contentType: String,
    val posterUrl: String = "",
    val anio: String = "",
) {
    val esPelicula: Boolean get() = contentType == "VOD"
    fun ref(): String = DituRef(contentId, contentType).codificar()
}

/** Un canal en vivo de Caracol, con el `assetId` que hace falta para resolverlo. */
internal data class DituCanal(
    val channelId: Int,
    val nombre: String,
    val logoUrl: String,
    val assetId: Int,
    val orden: Int = 0,
)

/**
 * Qué hay para ver en Caracol.
 *
 * El mismo endpoint sirve las dos cosas: `TRAY/SEARCH/VOD` con `query` vacío devuelve el catálogo
 * entero (unos 330 títulos en una sola llamada) y con `query` lleno, la búsqueda.
 */
internal class DituCatalogo(private val cliente: DituClienteLike) {

    suspend fun catalogo(): List<DituItem> = itemsDe(cliente.get(TRAY, mapOf("query" to "")))

    suspend fun buscar(q: String): List<DituItem> = itemsDe(cliente.get(TRAY, mapOf("query" to q)))

    suspend fun canales(): List<DituCanal> {
        val json = cliente.get(LIVECHANNELS, mapOf("orderBy" to "orderId", "sortOrder" to "asc"))
        return contenedoresDe(json).mapNotNull { canalDe(it) }
    }

    private fun itemsDe(json: JSONObject): List<DituItem> =
        contenedoresDe(json).mapNotNull { itemDe(it) }

    private fun itemDe(c: JSONObject): DituItem? {
        val m = c.optJSONObject("metadata") ?: JSONObject()
        val tipo = m.optString("contentType").uppercase()
        val subtipo = (m.optString("contentSubtype").ifBlank { m.optString("contentSubType") }).uppercase()
        val tipoDeRef = when {
            tipo == "BUNDLE" || tipo == "GROUP_OF_BUNDLES" -> tipo
            tipo == "VOD" && subtipo == "MOVIE" -> "VOD"
            // Todo lo demás (clips, LIVE, promos) no es algo que se pueda abrir como título.
            else -> return null
        }
        val id = c.optString("id").takeIf { it.isNotBlank() } ?: return null
        val titulo = m.optString("title").trim().takeIf { it.isNotBlank() } ?: return null
        return DituItem(
            contentId = id,
            titulo = titulo,
            contentType = tipoDeRef,
            posterUrl = posterDe(c),
            anio = anioDe(m),
        )
    }

    private fun canalDe(c: JSONObject): DituCanal? {
        val m = c.optJSONObject("metadata") ?: return null
        if (m.opt("isActive") != true) return null
        val id = m.optInt("channelId", 0).takeIf { it != 0 } ?: return null
        val nombre = m.optString("channelName").trim().takeIf { it.isNotBlank() } ?: return null
        val assets = c.optJSONArray("assets") ?: JSONArray()
        val lista = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
        // El assetId sale de ACÁ y no del EPG: el EPG devuelve `assets` vacío para el programa en
        // curso, así que ese viaje vuelve sin nada.
        val asset = lista.firstOrNull { it.optString("assetType") == "MASTER" && it.optInt("assetId", 0) != 0 }
            ?: lista.firstOrNull { it.optInt("assetId", 0) != 0 }
            ?: return null
        val logo = lista.firstNotNullOfOrNull { it.optString("logoMedium").takeIf { s -> s.isNotBlank() } }
            ?: lista.firstNotNullOfOrNull { it.optString("logoBig").takeIf { s -> s.isNotBlank() } }
            ?: lista.firstNotNullOfOrNull { it.optString("logoSmall").takeIf { s -> s.isNotBlank() } }
            ?: ""
        return DituCanal(
            channelId = id,
            nombre = nombre,
            logoUrl = logo,
            assetId = asset.optInt("assetId"),
            orden = m.optInt("orderId", 0),
        )
    }

    internal companion object {
        const val TRAY = "TRAY/SEARCH/VOD"
        const val LIVECHANNELS = "TRAY/LIVECHANNELS"
        const val CDN_IMAGENES = "https://image-registry.ditu.caracoltv.com/"
        const val POSTER = "portrait-thin-promotional-tablet.jpg"
        const val FONDO = "landscape-regular-clean-tablet.jpg"

        fun contenedoresDe(json: JSONObject): List<JSONObject> {
            val arr = json.optJSONObject("resultObj")?.optJSONArray("containers") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }

        /** Póster vertical del CDN propio; si no hay `pictureUrl`, el `icon` del `posterList`. */
        fun posterDe(c: JSONObject): String {
            val pic = (c.optJSONObject("metadata") ?: JSONObject()).optString("pictureUrl").trim()
            if (pic.isNotBlank()) return "$CDN_IMAGENES$pic/$POSTER"
            val arr = c.optJSONArray("posterList") ?: return ""
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .firstOrNull { it.optString("fileType") == "icon" }
                ?.optString("fileUrl").orEmpty()
        }

        /** Fondo apaisado del CDN propio; "" si no hay `pictureUrl`. */
        fun fondoDe(c: JSONObject): String {
            val pic = (c.optJSONObject("metadata") ?: JSONObject()).optString("pictureUrl").trim()
            return if (pic.isBlank()) "" else "$CDN_IMAGENES$pic/$FONDO"
        }

        fun anioDe(m: JSONObject): String {
            for (campo in listOf("releaseDate", "releaseYear", "year")) {
                val v = m.optString(campo)
                if (v.length >= 4 && v.take(4).all { it.isDigit() }) return v.take(4)
            }
            return ""
        }

        /** `assetId` del asset MASTER, o del primero que tenga uno. `null` si ninguno. */
        fun assetMaster(c: JSONObject): Int? {
            val arr = c.optJSONArray("assets") ?: return null
            val lista = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            return lista.firstOrNull { it.optString("assetType") == "MASTER" && it.optInt("assetId", 0) != 0 }
                ?.optInt("assetId")
                ?: lista.firstOrNull { it.optInt("assetId", 0) != 0 }?.optInt("assetId")
        }
    }
}
