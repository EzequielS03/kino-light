package com.arkiv.player.remote

import org.json.JSONObject

/**
 * Serializa [TvNowPlaying] al json que se guarda en `devices.nowPlaying`.
 * Sigue la convención de `cloudsync/SyncMappers`. `org.json` está disponible en tests JVM
 * (ver testImplementation en app/build.gradle.kts), así que el codec se testea sin Robolectric.
 */
object NowPlayingCodec {

    /** Versión del payload. Una foto de versión mayor se ignora en vez de leerse a medias. */
    const val VERSION = 1

    fun encode(p: TvNowPlaying): String = JSONObject().apply {
        put("v", VERSION)
        put("episodeId", p.episodeId)
        put("itemId", p.itemId)
        put("kind", p.kind)
        put("title", p.title)
        put("subtitle", p.subtitle)
        put("posterUrl", p.posterUrl)
        put("positionMs", p.positionMs)
        put("durationMs", p.durationMs)
        put("state", p.state.name)
        put("hasNext", p.hasNext)
        put("hasPrev", p.hasPrev)
        put("at", p.at)
        // Puramente aditivo: NO se sube VERSION por este campo. Un TV con el build viejo
        // simplemente no lo manda, decode() abajo lo lee como 0 y el comportamiento es exactamente
        // el de hoy. Si se subiera VERSION, decode() rechazaría TODA foto de un TV que todavía no
        // se actualizó (compara por igualdad, no admite versiones viejas) — rompería el feature
        // entero hasta reflashear los dos dispositivos, por un campo que ninguno de los dos
        // necesita para lo básico.
        put("startedAt", p.startedAtMs)
    }.toString()

    fun decode(raw: String?): TvNowPlaying? {
        if (raw.isNullOrBlank() || raw == "null" || raw == "{}") return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (o.optInt("v", 0) != VERSION) return null
        val episodeId = o.optString("episodeId")
        if (episodeId.isBlank()) return null
        val state = runCatching { TvPlaybackState.valueOf(o.optString("state")) }
            .getOrDefault(TvPlaybackState.PAUSED)
        return TvNowPlaying(
            episodeId = episodeId,
            itemId = o.optString("itemId"),
            kind = o.optString("kind"),
            title = o.optString("title"),
            subtitle = o.optString("subtitle"),
            posterUrl = o.optString("posterUrl"),
            positionMs = o.optLong("positionMs", 0),
            durationMs = o.optLong("durationMs", 0),
            state = state,
            hasNext = o.optBoolean("hasNext", false),
            hasPrev = o.optBoolean("hasPrev", false),
            at = o.optString("at"),
            startedAtMs = o.optLong("startedAt", 0),
        )
    }

    /**
     * Huella de los campos que representan un EVENTO (no el avance normal del tiempo). El publisher
     * la compara para decidir si publica ya: si incluyera positionMs, cada segundo sería un "cambio"
     * y el throttle no serviría de nada.
     */
    fun eventKey(p: TvNowPlaying): String = listOf(
        p.episodeId, p.itemId, p.kind, p.title, p.subtitle, p.posterUrl,
        p.durationMs.toString(), p.state.name, p.hasNext.toString(), p.hasPrev.toString(),
        // Reiniciar el MISMO episodio (mismo episodeId, nuevo startedAtMs) es un evento: hay que
        // publicarlo ya, no esperar al latido de 10s — es la señal que el celu necesita para notar
        // el reinicio (ver MarcaFuente/sellarMarca en NowPlayingCoordinator).
        p.startedAtMs.toString(),
    ).joinToString("|")
}
