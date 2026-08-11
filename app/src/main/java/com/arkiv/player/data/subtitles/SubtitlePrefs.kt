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
    /** En qué ORDEN elegir la pista de audio. Solo eso: no dice nada sobre qué idiomas entendés. */
    val audioLangs: List<TrackLang> = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH, TrackLang.DUAL),
    /**
     * Los idiomas que entendés lo bastante como para no necesitar subtítulos. Es un CONJUNTO: el
     * orden no significa nada acá, se guarda como lista solo para serializarla con el mismo helper
     * que las otras dos. Va separado de [audioLangs] a propósito: elegir a mano el audio japonés de
     * un anime lo sube al tope de [audioLangs] (ver `LangPromotion`), y si esa misma lista decidiera
     * los subtítulos, esa elección te dejaría el anime en japonés y SIN subtítulos para siempre.
     */
    val understoodLangs: List<TrackLang> = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH, TrackLang.DUAL),
    val subtitleLangs: List<TrackLang> = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH),
    /** AUTO = prenderlos solo si el audio quedó FUERA de [understoodLangs]. OFF = nunca solos. */
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
        .put("understoodLangs", JSONArray(understoodLangs.map { it.name }))
        .put("subtitleLangs", JSONArray(subtitleLangs.map { it.name }))
        .put("subtitleMode", subtitleMode.name)
        .put("sizePercent", sizePercent)
        .put("textColor", textColor).put("backgroundColor", backgroundColor)
        .put("edge", edge).toString()

    companion object {
        const val EDGE_NONE = 0
        const val EDGE_OUTLINE = 1
        const val EDGE_SHADOW = 2

        /**
         * [base] es lo que se usa para TODO campo que el JSON no traiga. Con el default (unas prefs
         * recién hechas) se comporta como siempre; el sync le pasa las preferencias actuales del
         * dispositivo para que un JSON de una build vieja —que no conoce las listas de idioma— no
         * las borre. Ver [SubtitlePrefs.applyFromRemote].
         */
        fun fromJson(s: String, base: PlaybackPrefs = PlaybackPrefs()): PlaybackPrefs? = runCatching {
            val o = JSONObject(s)
            PlaybackPrefs(
                audioLangs = langs(o, "audioLangs") ?: base.audioLangs,
                // Migración: sin el campo nuevo se siembra desde audioLangs, que hasta ahora cargaba
                // los dos significados. Así un usuario que ya tenía su lista armada sigue viendo
                // exactamente lo mismo que antes, en vez de volver de golpe a los defaults.
                understoodLangs = langs(o, "understoodLangs") ?: langs(o, "audioLangs")
                    ?: base.understoodLangs,
                subtitleLangs = langs(o, "subtitleLangs") ?: base.subtitleLangs,
                // Sin campo nuevo, se migra el legacy: "off" → OFF, cualquier otra cosa → AUTO.
                subtitleMode = o.optString("subtitleMode").takeIf { it.isNotBlank() }
                    ?.let { name -> runCatching { SubtitleMode.valueOf(name) }.getOrNull() }
                    ?: if (o.optString("language") == "off") SubtitleMode.OFF else SubtitleMode.AUTO,
                sizePercent = o.optInt("sizePercent", base.sizePercent),
                textColor = o.optLong("textColor", base.textColor),
                backgroundColor = o.optLong("backgroundColor", base.backgroundColor),
                edge = o.optInt("edge", base.edge),
            )
        }.getOrNull()

        /**
         * null cuando no hay nada utilizable en [key] → el que llama cae a su valor de respaldo. Se
         * devuelve null también con una lista vacía o con puros nombres desconocidos (una build
         * futura que agregue un `TrackLang`): quedarse con la lista vacía sería "nunca elegir audio"
         * y "siempre poner subtítulos", que es peor que ignorar lo que no se entiende.
         */
        private fun langs(o: JSONObject, key: String): List<TrackLang>? {
            val arr = o.optJSONArray(key) ?: return null
            return (0 until arr.length()).mapNotNull { i ->
                runCatching { TrackLang.valueOf(arr.getString(i)) }.getOrNull()
            }.ifEmpty { null }
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

    /**
     * Aplica preferencias recibidas del otro dispositivo (sync) sin re-emitir hacia afuera. Se
     * MEZCLA sobre lo que ya hay en vez de reemplazar: el celular y el Fire Stick se actualizan por
     * separado, así que es normal que el TV corra una build vieja. Su JSON no trae las listas de
     * idioma, y con un reemplazo entero un simple "subile el tamaño de la letra" desde el TV le
     * borraba al celular los idiomas configurados.
     */
    fun applyFromRemote(json: String) {
        PlaybackPrefs.fromJson(json, base = _prefs.value)?.let { update(it) }
    }

    private fun read(): PlaybackPrefs =
        store.getString(KEY, null)?.let { PlaybackPrefs.fromJson(it) } ?: PlaybackPrefs()

    private companion object {
        const val KEY = "subtitle_style"
    }
}
