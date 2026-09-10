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
 * La obra de la que se piden datos curiosos: su identidad, sin su nombre. El nombre se busca aparte
 * y solo si hace falta (puede costar una llamada a TMDB): ver [DatosCuriosos.de].
 */
internal data class ObraDeDatos(
    val tipo: String,
    val tmdbId: Int?,
    val tituloCanonico: String?,
    val temporada: Int?,
    val episodio: Int?,
) {
    /** tipo + tmdbId (o el título canónico) + temporada + capítulo: la clave del caché. */
    val clave: String
        get() {
            val quien = tmdbId?.takeIf { it > 0 }?.toString() ?: tituloCanonico.orEmpty().trim().lowercase()
            return "$tipo:$quien:${temporada ?: 0}:${episodio ?: 0}"
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

/** El prompt del gateway (`arkiv-api/src/arkiv_api/trivia/datos.py`), tal cual, y su limpieza. */
internal object PreguntaDeDatos {
    const val CUANTOS = 8
    /** Va en el código y no solo en el prompt: "corto" es algo que un modelo respeta a veces. */
    const val LARGO_MAXIMO = 220

    /**
     * Si es un capítulo, se pregunta por ESE capítulo: el gateway midió que así salen datos del
     * capítulo (director, guionista, estreno) y no genéricos de la serie.
     */
    fun instruccion(nombre: String, temporada: Int?, episodio: Int?): String {
        val obra = when {
            temporada != null && episodio != null -> "$nombre, temporada $temporada, episodio $episodio"
            episodio != null -> "$nombre, episodio $episodio"
            else -> nombre
        }
        return "Dame $CUANTOS datos curiosos y verificables sobre $obra. Cada uno UNA sola frase " +
            "corta, en español de Colombia, sin voseo, de menos de $LARGO_MAXIMO caracteres. " +
            "SIN SPOILERS: nada de lo que pasa en la trama, ni finales, ni giros. Habla de " +
            "producción, doblaje, música, reparto, rodaje, recepción o contexto histórico. " +
            "Responde SOLO un arreglo JSON de cadenas, sin texto alrededor."
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
 * segundos. Una respuesta que se lee pero queda vacía tras limpiar tampoco.
 */
internal class DatosCuriosos(
    private val ia: suspend (String) -> RespuestaDeIa,
    private val cache: CacheDeDatos,
) {
    suspend fun de(obra: ObraDeDatos, nombre: suspend () -> String?): List<String> {
        cache.leer(obra.clave)?.let { return it }
        val cual = nombre()?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val r = ia(PreguntaDeDatos.instruccion(cual, obra.temporada, obra.episodio))
        if (r !is RespuestaDeIa.Texto) return emptyList()
        val datos = try {
            PreguntaDeDatos.limpiar(JsonDelModelo.arreglo(r.texto))
        } catch (e: JsonIlegible) {
            Log.w(TAG, "respuesta ilegible de ${r.modelo}: ${e.message}")
            return emptyList()
        }
        if (datos.isNotEmpty()) cache.guardar(obra.clave, datos)
        return datos
    }

    private companion object { const val TAG = "ArkivTrivia" }
}
