package com.arkiv.player.data.trivia

import android.util.Log
import com.arkiv.player.data.ia.JsonDelModelo
import com.arkiv.player.data.ia.JsonIlegible
import com.arkiv.player.data.ia.RespuestaDeIa
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * La obra de la que se piden datos curiosos: su identidad, sin su ficha. La ficha de hechos
 * verificados de TMDB se busca aparte y solo si hace falta (puede costar hasta 3 llamadas): ver
 * [DatosCuriosos.de].
 */
internal data class ObraDeDatos(
    val tipo: String,
    val tmdbId: Int?,
    val tituloCanonico: String?,
    val temporada: Int?,
    val episodio: Int?,
) {
    /**
     * tipo + tmdbId (o el título canónico) + temporada + capítulo: la clave del caché.
     *
     * El prefijo `v2:` es la versión del prompt y de la ficha anclada en TMDB (adenda de spec del
     * 2026-09-10): sin él, los datos inventados que ya están guardados con la clave vieja (hasta 30
     * días) se seguirían mostrando tal cual.
     */
    val clave: String
        get() {
            val quien = tmdbId?.takeIf { it > 0 }?.toString() ?: tituloCanonico.orEmpty().trim().lowercase()
            return "v2:$tipo:$quien:${temporada ?: 0}:${episodio ?: 0}"
        }

    internal companion object {
        /**
         * Null si no hay forma de nombrarla bien —ni `tmdbId` ni `tituloCanonico`—: preguntarle al
         * modelo a ciegas es la forma más rápida de que invente.
         */
        fun de(tipo: String, tmdbId: Int?, tituloCanonico: String?, temporada: Int?, episodio: Int?): ObraDeDatos? {
            val id = tmdbId?.takeIf { it > 0 }
            val titulo = tituloCanonico?.trim()?.takeIf { it.isNotEmpty() }
            if (id == null && titulo == null) return null
            return ObraDeDatos(tipo, id, titulo, temporada, episodio)
        }
    }
}

/**
 * El prompt ya no es el del gateway "tal cual" (adenda de spec del 2026-09-10): ahora ancla la
 * pregunta en una [FichaDeObra] de TMDB, para que el modelo tenga hechos verdaderos de dónde
 * apoyarse en vez de inventar sobre obras poco conocidas o muy nuevas.
 */
internal object PreguntaDeDatos {
    const val CUANTOS = 8
    /** Va en el código y no solo en el prompt: "corto" es algo que un modelo respeta a veces. */
    const val LARGO_MAXIMO = 220

    /**
     * Si es un capítulo, se pregunta por ESE capítulo (temporada, episodio y su nombre si la ficha
     * lo trae): el gateway midió que así salen datos del capítulo (director, guionista, estreno) y
     * no genéricos de la serie. La ficha va debajo, presentada como datos verificados de TMDB, y se
     * le pide al modelo que se apoye en ella y prefiera devolver poco —o nada— a inventar.
     */
    fun instruccion(ficha: FichaDeObra, temporada: Int?, episodio: Int?): String {
        val obra = buildString {
            append("«${ficha.nombre}»")
            if (episodio != null) {
                append(if (temporada != null) ", temporada $temporada, episodio $episodio" else ", episodio $episodio")
            }
            ficha.capitulo?.nombre?.let { append(", «$it»") }
        }
        val renglones = ficha.renglones()
        val bloqueDeFicha = if (renglones.isBlank()) "" else "\n\nDatos verificados de TMDB:\n$renglones"
        return "Dame hasta $CUANTOS datos curiosos y verificables sobre $obra.$bloqueDeFicha\n\n" +
            "Cada uno UNA sola frase corta, en español de Colombia, sin voseo, de menos de " +
            "$LARGO_MAXIMO caracteres. SIN SPOILERS: nada de lo que pasa en la trama, ni finales, " +
            "ni giros. Habla de producción, doblaje, música, reparto, rodaje, recepción o contexto " +
            "histórico. Apóyate en los datos verificados de arriba cuando los haya: nunca " +
            "contradigas sus fechas ni sus nombres, y no incluyas un dato si no estás seguro de que " +
            "es cierto para ESTA obra exacta. Es mejor devolver pocos datos, o un arreglo vacío " +
            "([]), que inventar. Responde SOLO un arreglo JSON de cadenas, sin texto alrededor."
    }

    fun limpiar(arreglo: JSONArray): List<String> =
        (0 until arreglo.length())
            .mapNotNull { arreglo.opt(it) as? String }
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= LARGO_MAXIMO }
            .take(CUANTOS)
}

internal interface CacheDeDatos {
    fun leer(clave: String): List<String>?
    fun guardar(clave: String, datos: List<String>)
}

/**
 * El caché en archivos del aparato, 30 días por obra. Es solo un caché: perderlo cuesta volver a
 * preguntar, así que no merece una tabla de Room ni su migración.
 */
internal class CacheDeDatosEnDisco(private val dir: File, private val ahoraMs: () -> Long) : CacheDeDatos {

    override fun leer(clave: String): List<String>? = runCatching {
        val f = archivo(clave).takeIf { it.exists() } ?: return null
        val json = JSONObject(f.readText())
        if (ahoraMs() - json.getLong("t") >= VIGENCIA_MS) return null
        val arr = json.getJSONArray("d")
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrNull()

    override fun guardar(clave: String, datos: List<String>) {
        runCatching {
            dir.mkdirs()
            archivo(clave).writeText(JSONObject().put("t", ahoraMs()).put("d", JSONArray(datos)).toString())
        }
    }

    /** La clave puede traer títulos con cualquier carácter: el nombre del archivo es su hash. */
    private fun archivo(clave: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(clave.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.json")
    }

    internal companion object {
        const val VIGENCIA_MS = 30L * 24 * 60 * 60 * 1000
    }
}

/**
 * Los datos curiosos de una obra, o vacío. Nunca lanza: sin datos no hay botón, que es el fallo bueno
 * para algo accesorio.
 *
 * **Un fallo no se guarda**: sellarlo dejaría a la obra sin trivia un mes por una caída de treinta
 * segundos. Una respuesta ilegible tampoco, ni un arreglo que traía datos y quedó vacío tras limpiar
 * (eso es un tropiezo del modelo). Un `[]` que el modelo devolvió tal cual **sí** se guarda: desde la
 * adenda de spec del 2026-09-10 es una respuesta legítima ("no tengo nada seguro para esta obra"), y
 * no guardarla repreguntaría a Kilo (~20 s) cada vez que alguien vuelve a abrir la obra.
 */
internal class DatosCuriosos(
    private val ia: suspend (String) -> RespuestaDeIa,
    private val cache: CacheDeDatos,
) {
    suspend fun de(obra: ObraDeDatos, ficha: suspend () -> FichaDeObra?): List<String> {
        cache.leer(obra.clave)?.let { return it }
        val cual = ficha() ?: return emptyList()
        val r = ia(PreguntaDeDatos.instruccion(cual, obra.temporada, obra.episodio))
        if (r !is RespuestaDeIa.Texto) return emptyList()
        val crudo = try {
            JsonDelModelo.arreglo(r.texto)
        } catch (e: JsonIlegible) {
            Log.w(TAG, "respuesta ilegible de ${r.modelo}: ${e.message}")
            return emptyList()
        }
        if (crudo.length() == 0) {
            cache.guardar(obra.clave, emptyList())
            return emptyList()
        }
        val datos = PreguntaDeDatos.limpiar(crudo)
        if (datos.isNotEmpty()) cache.guardar(obra.clave, datos)
        return datos
    }

    private companion object { const val TAG = "ArkivTrivia" }
}
