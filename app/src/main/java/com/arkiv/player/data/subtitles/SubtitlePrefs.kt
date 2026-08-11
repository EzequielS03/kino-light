package com.arkiv.player.data.subtitles

import android.content.Context
import com.arkiv.player.playback.TrackLang
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Qué hacer con los subtítulos al arrancar. Ver [PlaybackPrefs.subtitleLangs]. */
enum class SubtitleMode { AUTO, OFF }

/**
 * Preferencias de reproducción: idioma de audio, idioma y estilo de subtítulos. Se persiste local y
 * se sincroniza al TV. Los idiomas son LISTAS ORDENADAS: el reproductor las recorre y toma la primera
 * pista que exista, en vez de quedarse con la primera del archivo.
 */
data class PlaybackPrefs(
    val audioLangs: List<TrackLang> = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH, TrackLang.DUAL),
    val subtitleLangs: List<TrackLang> = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH),
    /** AUTO = prenderlos solo si el audio quedó FUERA de [audioLangs]. OFF = nunca solos. */
    val subtitleMode: SubtitleMode = SubtitleMode.AUTO,
    val sizePercent: Int = 100,        // 60..200
    val textColor: Long = 0xFFFFFFFF,  // ARGB
    val backgroundColor: Long = 0x80000000, // ARGB (fondo de la caja)
    val edge: Int = EDGE_OUTLINE,      // 0 none, 1 outline, 2 drop shadow
) {
    /**
     * Códigos para `SubtitleApi.search(languages=…)`, en el orden del usuario y sin repetir. Los tres
     * buckets del español colapsan a `es` a propósito: es el único código español verificado contra el
     * gateway. Nunca devuelve vacío — OFF significa "no prenderlos solos", no "no buscar".
     */
    fun openSubtitlesCodes(): String = subtitleLangs.mapNotNull {
        when (it) {
            TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH -> "es"
            TrackLang.ENGLISH -> "en"
            TrackLang.JAPANESE -> "ja"
            TrackLang.DUAL, TrackLang.UNKNOWN -> null
        }
    }.distinct().joinToString(",").ifEmpty { "es" }

    fun toJson(): String = JSONObject()
        // Campo legacy: una build vieja lee SOLO esto y tiene que seguir funcionando.
        .put("language", if (subtitleMode == SubtitleMode.OFF) "off" else "es")
        .put("audioLangs", JSONArray(audioLangs.map { it.name }))
        .put("subtitleLangs", JSONArray(subtitleLangs.map { it.name }))
        .put("subtitleMode", subtitleMode.name)
        .put("sizePercent", sizePercent)
        .put("textColor", textColor).put("backgroundColor", backgroundColor)
        .put("edge", edge).toString()

    companion object {
        const val EDGE_NONE = 0
        const val EDGE_OUTLINE = 1
        const val EDGE_SHADOW = 2

        fun fromJson(s: String): PlaybackPrefs? = runCatching {
            val o = JSONObject(s)
            val default = PlaybackPrefs()
            PlaybackPrefs(
                audioLangs = langs(o, "audioLangs") ?: default.audioLangs,
                subtitleLangs = langs(o, "subtitleLangs") ?: default.subtitleLangs,
                // Sin campo nuevo, se migra el legacy: "off" → OFF, cualquier otra cosa → AUTO.
                subtitleMode = o.optString("subtitleMode").takeIf { it.isNotBlank() }
                    ?.let { name -> runCatching { SubtitleMode.valueOf(name) }.getOrNull() }
                    ?: if (o.optString("language") == "off") SubtitleMode.OFF else SubtitleMode.AUTO,
                sizePercent = o.optInt("sizePercent", default.sizePercent),
                textColor = o.optLong("textColor", default.textColor),
                backgroundColor = o.optLong("backgroundColor", default.backgroundColor),
                edge = o.optInt("edge", default.edge),
            )
        }.getOrNull()

        /** null si el campo no está (→ usar el default), lista si está aunque venga vacía. */
        private fun langs(o: JSONObject, key: String): List<TrackLang>? {
            val arr = o.optJSONArray(key) ?: return null
            return (0 until arr.length()).mapNotNull { i ->
                runCatching { TrackLang.valueOf(arr.getString(i)) }.getOrNull()
            }
        }
    }
}

/** Preferencias persistidas localmente (SharedPreferences), observables. */
class SubtitlePrefs(context: Context) {
    private val store = context.applicationContext.getSharedPreferences("arkiv_subs", Context.MODE_PRIVATE)

    private val _prefs = MutableStateFlow(read())
    val prefs: StateFlow<PlaybackPrefs> = _prefs.asStateFlow()

    fun update(p: PlaybackPrefs) {
        _prefs.value = p
        store.edit().putString(KEY, p.toJson()).apply()
    }

    /** Aplica preferencias recibidas del otro dispositivo (sync) sin re-emitir hacia afuera. */
    fun applyFromRemote(json: String) {
        PlaybackPrefs.fromJson(json)?.let { update(it) }
    }

    private fun read(): PlaybackPrefs =
        store.getString(KEY, null)?.let { PlaybackPrefs.fromJson(it) } ?: PlaybackPrefs()

    private companion object {
        const val KEY = "subtitle_style"
    }
}
