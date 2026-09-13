package com.arkiv.player.data.recomendaciones

import android.util.Log
import com.arkiv.player.data.db.RecomendacionEntity
import com.arkiv.player.data.ia.ModelJson
import com.arkiv.player.data.ia.UnreadableJson
import com.arkiv.player.data.ia.AiResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/** Cuándo toca volver a generar. Ver el KDoc de [GeneradorParaTi]. */
internal object PuertaDeParaTi {
    const val VENTANA_MS = 24 * 60 * 60 * 1000L
    /** Un modelo caído no gasta la ventana entera, pero tampoco se martilla: quince minutos. */
    const val VENTANA_TRAS_FALLO_MS = 15 * 60 * 1000L

    fun toca(ultimoIntentoMs: Long, ultimoFueFalloDelModelo: Boolean, ahoraMs: Long): Boolean {
        if (ultimoIntentoMs <= 0L) return true
        val ventana = if (ultimoFueFalloDelModelo) VENTANA_TRAS_FALLO_MS else VENTANA_MS
        return ahoraMs - ultimoIntentoMs >= ventana
    }
}

/** El prompt del gateway (`recomendaciones/modelo.py`), tal cual, y la lectura de su respuesta. */
internal object PreguntaParaTi {
    const val CUANTOS_PIDE = 20
    private val DIGITOS = Regex("[0-9]+")

    /**
     * Conserva la palabra *repetido* aunque la app no la mande: se porta tal cual para no tocar un
     * prompt que el gateway afinó midiendo.
     */
    fun instruccion(renglones: String): String =
        "Eres un recomendador de películas y series para una persona de Colombia. " +
            "Te doy lo que vio: 'terminado' le gustó, 'abandonado' lo dejó (NO propongas nada " +
            "parecido), 'repetido' le gustó mucho. Propón $CUANTOS_PIDE títulos que NO estén en la " +
            "lista. Responde SOLO un arreglo JSON, sin texto alrededor, con objetos " +
            "{\"titulo\",\"anio\",\"tipo\",\"porque\"}. \"tipo\" es \"movie\" o \"tv\". " +
            "\"porque\" es UNA frase corta en español de Colombia, sin voseo, que explique la " +
            "relación con lo que vio. Nada de contenido para adultos." +
            "\n\n$renglones"

    /** `_parsear` del gateway. Lanza [UnreadableJson] si no vino ningún arreglo. */
    fun candidatos(texto: String): List<Candidato> {
        val arr = ModelJson.array(texto)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val titulo = o.optString("titulo").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            // Perder el año de UN candidato no puede tumbar a los otros diecinueve.
            val anio = when (val a = o.opt("anio")) {
                is Int -> a.toString()
                is String -> a.trim().takeIf { DIGITOS.matches(it) }.orEmpty()
                else -> ""
            }
            Candidato(
                titulo = titulo,
                anio = anio,
                tipo = if (o.optString("tipo") == "tv") "tv" else "movie",
                porque = o.optString("porque").trim(),
            )
        }
    }
}

/**
 * Genera la fila "Para ti" en el aparato, con Kilo. Port del orquestador del gateway
 * (`recomendaciones/generador.py`).
 *
 * Tres reglas mandan:
 * 1. **Deduplicación por tiempo** ([PuertaDeParaTi]): el disparo es "terminaste algo", que en una
 *    maratón pasa veinte veces en una tarde. La ventana hace que eso sea un solo cálculo.
 * 2. **Un fallo nunca empeora lo que ya había**: sin respuesta del modelo, o sin ninguna verificada,
 *    las recomendaciones anteriores se quedan.
 * 3. **Nunca lanza**: corre en `applicationScope`, que no tiene manejador de excepciones. Una
 *    excepción inesperada se anota como fallo (reintento a los 15 min) y se traga.
 */
internal class GeneradorParaTi(
    private val ia: suspend (String) -> AiResponse,
    private val historial: suspend () -> List<Vista>,
    private val yaVistos: suspend () -> Set<String>,
    private val verificar: suspend (List<Candidato>, Set<String>) -> List<Verificada>,
    private val guardar: suspend (List<RecomendacionEntity>) -> Unit,
    private val leerMarcas: () -> Pair<Long, Boolean>,
    private val escribirMarcas: (Long, Boolean) -> Unit,
    private val ahoraMs: () -> Long = { System.currentTimeMillis() },
) {
    /** Una sola generación a la vez: dos disparos seguidos no pueden pasar la puerta los dos. */
    private val enCurso = Mutex()

    suspend fun generarSiToca() {
        if (!enCurso.tryLock()) return
        val ahora = ahoraMs()
        try {
            val (ultimo, falloDelModelo) = leerMarcas()
            if (!PuertaDeParaTi.toca(ultimo, falloDelModelo, ahora)) return
            val vistas = historial()
            if (vistas.isEmpty()) return
            escribirMarcas(ahora, false)

            val r = ia(PreguntaParaTi.instruccion(SenalesDeHistorial.renglones(vistas)))
            val candidatos = (r as? AiResponse.Text)?.let {
                try { PreguntaParaTi.candidatos(it.text) } catch (e: UnreadableJson) { null }
            }
            if (candidatos.isNullOrEmpty()) {
                Log.w(TAG, "the model gave no candidates: keeping the previous recommendations")
                escribirMarcas(ahora, true)
                return
            }

            val verificadas = verificar(candidatos, yaVistos())
            val filas = verificadas.mapNotNull { v ->
                val destino = GuardadoDeRecomendacion.destinoDeRef(v.ref) ?: return@mapNotNull null
                v to GuardadoDeRecomendacion.itemIdDe(destino)
            }.distinctBy { it.second }.mapIndexed { i, (v, id) ->
                RecomendacionEntity(
                    id = id, tmdbId = v.tmdbId, tipo = v.tipo, titulo = v.titulo,
                    posterUrl = v.posterUrl, porque = v.candidato.porque, ref = v.ref,
                    orden = i, generadoAt = ahora, updatedAt = ahora,
                )
            }
            if (filas.isEmpty()) {
                Log.w(TAG, "none verified out of ${candidatos.size}: keeping the previous ones")
                return
            }
            guardar(filas)
            Log.w(TAG, "${filas.size} new recommendations out of ${candidatos.size} candidates")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "generation failed: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { escribirMarcas(ahora, true) }
        } finally {
            enCurso.unlock()
        }
    }

    private companion object { const val TAG = "ArkivParaTi" }
}
