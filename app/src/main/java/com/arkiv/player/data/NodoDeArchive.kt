package com.arkiv.player.data

import com.arkiv.player.playback.PoliticaOrigen
import org.json.JSONObject

/**
 * Hablarle directo al servidor que tiene el archivo, sin pasar por el redirector de archive.org.
 *
 * El porqué, medido el 2026-08-10 sobre el ítem `get-backers-05-…`: la app no podía reproducir
 * ninguno de sus 26 capítulos en español porque `https://archive.org/download/…` devolvía 503, y
 * después 500, y después un 302 que moría. Parecía contenido perdido. **No lo estaba**: bajando los
 * archivos de control del propio nodo, `_meta.xml` y `_files.xml` eran XML válidos, el directorio
 * estaba limpio, y el mp4 del capítulo 19 se sirvió entero — 206 en 0,73 s. Lo único roto era
 * `download.php`, que es la puerta de entrada, no el contenido.
 *
 * Medido en tandas de dos, el mismo minuto:
 *
 * | vía                     | intento 1 | intento 2 |
 * |-------------------------|-----------|-----------|
 * | download.php + Range    | 302 (muere) | 500     |
 * | download.php sin Range  | 500       | 500       |
 * | nodo directo + Range    | **206**   | **206**   |
 *
 * Así que cuando el redirector falla queda esta salida: `/metadata/` (que es otro servicio, y
 * seguía respondiendo) dice en qué servidor y en qué carpeta está el ítem, y con eso se arma la URL
 * real.
 *
 * **Estas URLs no se cachean nunca.** Los ítems se mueven de servidor, y una URL de nodo guardada
 * envejece hasta apuntar a la nada — que es justo lo contrario de la URL canónica `/download/`, que
 * es estable para siempre a cambio de depender del redirector. Por eso el camino normal sigue
 * siendo el canónico y esto es solo el plan B, resuelto de nuevo cada vez que hace falta.
 */
object NodoDeArchive {

    /**
     * Si un fallo del redirector justifica ir a buscar el archivo al nodo.
     *
     * Solo los fallos que son *de la puerta de entrada*: 5xx y quedarse sin respuesta. Un 404 no
     * —si el archivo no está catalogado, el nodo va a decir lo mismo más lento— ni un 403, que es
     * de permisos y viaja con el ítem, no con el servidor.
     */
    fun valeIntentarNodo(code: Int): Boolean =
        code == PoliticaOrigen.SIN_RESPUESTA || code in 500..599

    /**
     * `(identifier, ruta)` de una URL canónica de descarga, o null si no lo es.
     *
     * La ruta vuelve **tal como venía, todavía percent-encoded**. Decodificarla acá para volver a
     * codificarla al armar la URL del nodo es la forma segura de romper los nombres que traen '@',
     * '%' o espacios — y estos ítems están llenos de los tres.
     */
    fun partesDeUrlDeDescarga(url: String): Pair<String, String>? {
        val marca = "://archive.org/download/"
        val i = url.indexOf(marca)
        if (i < 0) return null
        val resto = url.substring(i + marca.length)
        val corte = resto.indexOf('/')
        if (corte <= 0) return null
        val identifier = resto.substring(0, corte)
        val ruta = resto.substring(corte + 1).substringBefore('?')
        if (identifier.isBlank() || ruta.isBlank()) return null
        return identifier to ruta
    }

    /**
     * URLs directas al archivo, del nodo primario primero y después los alternos.
     *
     * Devuelve lista vacía si la metadata no sirve (vacía, ilegible, o el `{"error":…}` que archive
     * responde cuando ni siquiera puede leer el ítem): sin servidor confirmado no hay nada que
     * inventar, y probar una URL adivinada solo gasta tiempo.
     */
    fun urlsDesdeMetadata(json: String, ruta: String): List<String> {
        val raiz = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val urls = LinkedHashSet<String>()

        fun agregar(server: String?, dir: String?) {
            if (server.isNullOrBlank() || dir.isNullOrBlank()) return
            urls += "https://$server${dir.trimEnd('/')}/$ruta"
        }

        agregar(raiz.optString("server").ifBlank { null }, raiz.optString("dir").ifBlank { null })

        val alternos = raiz.optJSONObject("alternate_locations")
        // `workable` son los que archive considera sanos ahora mismo; `servers` es la lista
        // completa. Se miran los dos, en ese orden, y el LinkedHashSet se encarga de que el que
        // aparezca en ambas no gaste dos intentos.
        listOf("workable", "servers").forEach { clave ->
            val lista = alternos?.optJSONArray(clave) ?: return@forEach
            for (n in 0 until lista.length()) {
                val o = lista.optJSONObject(n) ?: continue
                agregar(o.optString("server").ifBlank { null }, o.optString("dir").ifBlank { null })
            }
        }
        return urls.toList()
    }
}
