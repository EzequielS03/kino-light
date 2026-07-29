package com.arkiv.player.data.catalog.mirror

import com.arkiv.player.data.catalog.TorrentLang
import com.arkiv.player.data.catalog.TorrentResult

/** Mapea el `lang_norm` del backend al enum de idioma de la app. */
object MirrorLang {
    fun fromNorm(norm: String?): TorrentLang = when (norm?.trim()?.uppercase()) {
        "LATINO" -> TorrentLang.LATINO
        "CASTELLANO", "ESPAÑOL", "ESPANOL", "ES" -> TorrentLang.CASTELLANO
        "DUAL" -> TorrentLang.DUAL
        "VOSE", "JAP_SUB", "SUBTITULADO", "SUB" -> TorrentLang.JAP_SUB
        "INGLES", "INGLÉS", "ENGLISH", "EN" -> TorrentLang.ENGLISH
        else -> TorrentLang.OTHER
    }
}

/** Convierte un [MirrorTorrent] del backend en el [TorrentResult] que consume la UI/player. */
object MirrorMapper {
    fun toResult(t: MirrorTorrent): TorrentResult {
        val display = t.name?.takeIf { it.isNotBlank() } ?: synthName(t)
        return TorrentResult(
            name = display,
            seeders = t.seeders ?: 0,
            sizeBytes = t.sizeBytes,
            lang = MirrorLang.fromNorm(t.langNorm),
            infoHash = t.infohash,
            magnetUri = t.magnet,
            downloadUrl = null,
        )
    }

    /**
     * Nombre para mostrar cuando el backend no trae `name` (habitual: casi todo el anime/series lo
     * traen null). Antepone temporada/episodio (lo más útil para navegar) y agrega calidad —que
     * además necesita `qualityRank` para ordenar—, idioma y tamaño.
     */
    private fun synthName(t: MirrorTorrent): String {
        val parts = buildList {
            episodeLabel(t)?.let { add(it) }
            t.quality?.let { add(it) }
            t.langRaw?.let { add(it) }
            t.sizeLabel?.let { add(it) }
        }
        return parts.joinToString(" · ").ifBlank { t.source ?: "Torrent" }
    }

    private fun episodeLabel(t: MirrorTorrent): String? = when {
        t.isPack && t.episode != null && t.episodeEnd != null ->
            "Pack E${t.episode}-${t.episodeEnd}" + (t.season?.let { " (T$it)" } ?: "")
        t.isPack -> "Pack" + (t.season?.let { " T$it" } ?: "")
        t.season != null && t.episode != null -> "T${t.season} · E${t.episode}"
        t.episode != null -> "E${t.episode}"
        else -> null
    }
}
