package com.arkiv.player.torrent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Trackers públicos por defecto (fallback si no hay red ni asset), SIEMPRE inyectados además de la
 *  lista ngosang. Incluye trackers específicos de ANIME (nyaa) que la lista "best" general de ngosang
 *  no trae — clave para que los torrents de anime encuentren peers (idea tomada de animeko). */
val DEFAULT_TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.tracker.cl:1337/announce",
    "udp://open.demonii.com:1337/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://open.stealth.si:80/announce",
    "udp://explodie.org:6969/announce",
    "https://tracker.tamersunion.org:443/announce",
    // Anime (nyaa): imprescindibles para descubrir peers en torrents de anime.
    "http://nyaa.tracker.wf:7777/announce",
    "http://t.nyaatracker.com:80/announce",
    "http://anidex.moe:6969/announce",
)

/** Parsea el trackers_best.txt de ngosang (una URL por línea, líneas en blanco entre medias).
 *  Puro/testeable: mantiene udp/http/https, descarta wss (webtorrent, libtorrent los rechaza) y vacíos. */
fun parseTrackers(text: String): List<String> =
    text.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("udp://") || it.startsWith("http://") || it.startsWith("https://") }
        .distinct()
        .toList()

/**
 * Provee la lista de trackers a inyectar en los magnets. Prioridad: remoto cacheado (disco) → asset
 * bundled → DEFAULT. Siempre en unión con DEFAULT (nunca menos que el fallback). Se refresca con [refresh].
 */
class TrackerListProvider(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build(),
) {
    private val appContext = context.applicationContext
    private val cacheFile = File(appContext.filesDir, "trackers_best.txt")

    @Volatile private var trackers: List<String> = load()

    /** Lista actual (unión de la fuente vigente con DEFAULT, deduplicada). */
    fun current(): List<String> = trackers

    private fun load(): List<String> {
        val fromDisk = runCatching { if (cacheFile.exists()) parseTrackers(cacheFile.readText()) else null }.getOrNull()
        val fromAsset = runCatching {
            appContext.assets.open("trackers_best.txt").bufferedReader().use { parseTrackers(it.readText()) }
        }.getOrDefault(emptyList())
        val base = (fromDisk?.takeIf { it.isNotEmpty() } ?: fromAsset)
        return (base + DEFAULT_TRACKERS).distinct().ifEmpty { DEFAULT_TRACKERS }
    }

    /** Baja la lista fresca de ngosang y la cachea. Best-effort: si falla, mantiene la actual. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val body = runCatching {
            client.newCall(Request.Builder().url(URL).build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return@withContext
        val parsed = parseTrackers(body)
        if (parsed.isNotEmpty()) {
            runCatching { cacheFile.writeText(body) }
            trackers = (parsed + DEFAULT_TRACKERS).distinct()
            Log.i("ArkivTorrent", "trackers actualizados: ${parsed.size} de ngosang (+${DEFAULT_TRACKERS.size} fallback)")
        }
    }

    private companion object {
        const val URL = "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"
    }
}
