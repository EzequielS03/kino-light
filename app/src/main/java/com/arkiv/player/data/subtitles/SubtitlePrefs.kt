package com.arkiv.player.data.subtitles

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Estilo + idioma preferido de subtítulos. Se persiste local y se sincroniza al TV. */
data class SubtitleStyle(
    val language: String = "es",       // idioma preferido para auto-cargar (ISO)
    val sizePercent: Int = 100,        // 60..200
    val textColor: Long = 0xFFFFFFFF,  // ARGB
    val backgroundColor: Long = 0x80000000, // ARGB (fondo de la caja)
    val edge: Int = EDGE_OUTLINE,      // 0 none, 1 outline, 2 drop shadow
) {
    fun toJson(): String = JSONObject()
        .put("language", language).put("sizePercent", sizePercent)
        .put("textColor", textColor).put("backgroundColor", backgroundColor)
        .put("edge", edge).toString()

    companion object {
        const val EDGE_NONE = 0
        const val EDGE_OUTLINE = 1
        const val EDGE_SHADOW = 2

        fun fromJson(s: String): SubtitleStyle? = runCatching {
            val o = JSONObject(s)
            SubtitleStyle(
                language = o.optString("language", "es"),
                sizePercent = o.optInt("sizePercent", 100),
                textColor = o.optLong("textColor", 0xFFFFFFFF),
                backgroundColor = o.optLong("backgroundColor", 0x80000000),
                edge = o.optInt("edge", EDGE_OUTLINE),
            )
        }.getOrNull()
    }
}

/** Preferencias de subtítulos persistidas localmente (SharedPreferences), observables. */
class SubtitlePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("arkiv_subs", Context.MODE_PRIVATE)

    private val _style = MutableStateFlow(read())
    val style: StateFlow<SubtitleStyle> = _style.asStateFlow()

    fun update(style: SubtitleStyle) {
        _style.value = style
        prefs.edit().putString(KEY, style.toJson()).apply()
    }

    /** Aplica un estilo recibido del otro dispositivo (sync) sin re-emitir hacia afuera. */
    fun applyFromRemote(json: String) {
        SubtitleStyle.fromJson(json)?.let { update(it) }
    }

    private fun read(): SubtitleStyle =
        prefs.getString(KEY, null)?.let { SubtitleStyle.fromJson(it) } ?: SubtitleStyle()

    private companion object {
        const val KEY = "subtitle_style"
    }
}
