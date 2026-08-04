package com.arkiv.player.data.offline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Un episodio a descargar, tal como lo entiende arkiv-offline (fuente = pageUrl de la capa web). */
data class NucDownloadItem(val season: Int, val episode: Int, val pageUrl: String)

data class NucJobItem(val itemId: Long, val season: Int, val episode: Int, val status: String, val error: String?)
data class NucJob(val jobId: Long, val status: String, val progress: Float?, val items: List<NucJobItem>)
data class NucLibraryEntry(val itemId: Long, val season: Int, val episode: Int, val sizeBytes: Long)

/**
 * Cliente REST de arkiv-offline (Flask en el NUC de casa): crea/consulta/borra jobs de descarga y
 * consulta/borra la biblioteca ya descargada. Resuelve LAN-vs-túnel probando la LAN primero (rápido
 * en casa) y cayendo al túnel de Cloudflare si no responde (fuera de casa).
 */
class ArkivOfflineApi(
    private val lanBaseUrl: () -> String,
    private val tunnelBaseUrl: () -> String,
    private val apiKey: () -> String,
    // Timeout corto: esto solo decide LAN-vs-túnel, no espera una respuesta de negocio lenta.
    private val lanProbeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    /** LAN si responde, si no el túnel. Usado también por el cliente SSE (progreso de jobs). */
    suspend fun baseUrlResolved(): String = withContext(Dispatchers.IO) {
        val lan = lanBaseUrl()
        val reachable = runCatching {
            lanProbeClient.newCall(
                Request.Builder().url("$lan/library?series_id=__probe__")
                    .header("X-Api-Key", apiKey()).build(),
            ).execute().use { it.isSuccessful || it.code == 404 }
        }.getOrDefault(false)
        if (reachable) lan else tunnelBaseUrl()
    }

    /** URL del stream de un item de biblioteca; la consume libVLC directamente, no este cliente. */
    fun streamUrl(itemId: Long, baseUrl: String): String =
        "$baseUrl/stream/$itemId?api_key=${apiKey()}"

    suspend fun createJob(seriesId: String, showTitle: String, posterUrl: String, items: List<NucDownloadItem>): Long? =
        withContext(Dispatchers.IO) {
            val base = baseUrlResolved()
            val itemsJson = JSONArray()
            items.forEach { i ->
                itemsJson.put(
                    JSONObject().apply {
                        put("season", i.season)
                        put("episode", i.episode)
                        put("source_ref", i.pageUrl)
                    },
                )
            }
            val body = JSONObject().apply {
                put("kind", "web")
                put("series_id", seriesId)
                put("show_title", showTitle)
                put("poster_url", posterUrl)
                put("items", itemsJson)
            }.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$base/jobs").post(body)
                .header("X-Api-Key", apiKey()).build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    JSONObject(resp.body?.string() ?: return@withContext null).optLong("job_id").takeIf { it > 0 }
                }
            }.getOrNull()
        }

    suspend fun getJob(jobId: Long): NucJob? = withContext(Dispatchers.IO) {
        val base = baseUrlResolved()
        val req = Request.Builder().url("$base/jobs/$jobId")
            .header("X-Api-Key", apiKey()).build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                parseJob(JSONObject(resp.body?.string() ?: return@withContext null))
            }
        }.getOrNull()
    }

    suspend fun deleteJob(jobId: Long): Boolean = withContext(Dispatchers.IO) {
        val base = baseUrlResolved()
        val req = Request.Builder().url("$base/jobs/$jobId").delete()
            .header("X-Api-Key", apiKey()).build()
        runCatching { client.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    suspend fun library(seriesId: String): List<NucLibraryEntry> = withContext(Dispatchers.IO) {
        val base = baseUrlResolved()
        val req = Request.Builder().url("$base/library?series_id=$seriesId")
            .header("X-Api-Key", apiKey()).build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val arr = JSONArray(resp.body?.string() ?: return@withContext emptyList())
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    NucLibraryEntry(o.getLong("id"), o.getInt("season"), o.getInt("episode"), o.optLong("size_bytes"))
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun deleteLibraryItem(itemId: Long): Boolean = withContext(Dispatchers.IO) {
        val base = baseUrlResolved()
        val req = Request.Builder().url("$base/library/$itemId").delete()
            .header("X-Api-Key", apiKey()).build()
        runCatching { client.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    internal fun parseJob(o: JSONObject): NucJob {
        val itemsArr = o.getJSONArray("items")
        val items = (0 until itemsArr.length()).map { i ->
            val it = itemsArr.getJSONObject(i)
            NucJobItem(
                it.getLong("id"), it.getInt("season"), it.getInt("episode"),
                it.getString("status"), it.optString("error").ifBlank { null },
            )
        }
        val progress = if (o.isNull("progress")) null else o.optDouble("progress").toFloat()
        return NucJob(o.getLong("id"), o.getString("status"), progress, items)
    }
}
