package com.arkiv.player.data.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Un release de anime (torrent) de AnimeTosho. */
data class AnimeRelease(
    val title: String,
    val magnet: String,
    val seeders: Int,
    val sizeLabel: String,
)

/**
 * Cliente de anime vía AnimeTosho (feed JSON, sin Cloudflare, indexa nyaa y otros).
 * nyaa.si suele estar bloqueado por DNS; AnimeTosho es alcanzable.
 */
class AnimeApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    /** Busca releases. Sin query, devuelve los más recientes. */
    suspend fun search(query: String?): List<AnimeRelease> = withContext(Dispatchers.IO) {
        val url = buildString {
            append("https://feed.animetosho.org/json?only_tor=1")
            if (!query.isNullOrBlank()) append("&q=").append(URLEncoder.encode(query, "UTF-8"))
        }
        val json = runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build())
                .execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return@withContext emptyList()
        runCatching { parse(JSONArray(json)) }.getOrDefault(emptyList())
    }

    private fun parse(arr: JSONArray): List<AnimeRelease> =
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val magnet = o.optString("magnet_uri")
            if (!magnet.startsWith("magnet:")) return@mapNotNull null
            AnimeRelease(
                title = o.optString("title").ifBlank { o.optString("torrent_name") },
                magnet = withTrackers(magnet),
                seeders = o.optInt("seeders"),
                sizeLabel = humanSize(o.optLong("total_size")),
            )
        }.sortedByDescending { it.seeders }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1e9)
        bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1e6)
        bytes > 0 -> "%.0f KB".format(bytes / 1e3)
        else -> ""
    }

    private fun withTrackers(magnet: String): String =
        if (magnet.contains("&tr=")) magnet
        else magnet + TRACKERS.joinToString("") { "&tr=" + URLEncoder.encode(it, "UTF-8") }

    companion object {
        private val TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.torrent.eu.org:451/announce",
        )
    }
}
