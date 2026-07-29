package com.arkiv.player.playback

/**
 * Buckets de idioma normalizados. Diccionario robado del `set_lang()` de Alfa (unify.py:504): colapsa
 * las decenas de variantes que aparecen en nombres de pista de audio de VLC ("Track 1 - [Spanish]",
 * "Español (Latinoamérica)", "Castellano", "Dual", "es-419"…) o de releases a unos pocos buckets.
 */
enum class AudioLang { LATINO, CASTELLANO, SPANISH, DUAL, ENGLISH, JAPANESE, UNKNOWN }

/** Clasificador de idioma por texto libre. Portado del diccionario de Alfa/Balandro. */
object LangTokens {
    // OJO: `\w` de Java/Kotlin NO matchea `ñ`/acentos → se usan clases explícitas [nñ] y se aceptan las
    // formas sin acento (los releases suelen quitarlos: "Espanol", "Castellano").
    // "Español Latino"/"es-419"/"Audio Latino" → LATINO. Antes que CASTELLANO para no confundir.
    private val LATINO = Regex("latinoameric|\\blatino\\b|\\blat\\b|es-?419|espa[nñ]ol\\s*lat|audio\\s*lat|m[eé]xic")
    private val CASTELLANO = Regex("castellan|castilian|espa[nñ]a\\b|\\bspain\\b|es-?es\\b|\\bcast\\b")
    private val DUAL = Regex("\\bdual\\b|multi[- ]?audio|\\bmulti\\b")
    private val ENGLISH = Regex("\\benglish\\b|\\bingl[eé]s\\b|\\beng\\b|en-?us\\b|en-?gb\\b|\\[en\\]")
    private val JAPANESE = Regex("\\bjapanese\\b|\\bjapon[eé]s\\b|\\bjap\\b|\\bjpn?\\b|\\bvose\\b|\\bvo\\b")
    // Español genérico (sin marca latino/castellano): fallback que sirve a cualquier preferencia hispana.
    private val SPANISH = Regex("espa[nñ]ol|\\bspanish\\b|\\bspa\\b|\\besp\\b|castellano|latino|\\[es\\]|\\bes-?\\d*\\b")

    /** Clasifica un nombre de pista / release al bucket más específico posible. */
    fun classify(raw: String): AudioLang {
        val s = raw.lowercase()
        return when {
            LATINO.containsMatchIn(s) -> AudioLang.LATINO
            CASTELLANO.containsMatchIn(s) -> AudioLang.CASTELLANO
            DUAL.containsMatchIn(s) -> AudioLang.DUAL
            SPANISH.containsMatchIn(s) -> AudioLang.SPANISH
            JAPANESE.containsMatchIn(s) -> AudioLang.JAPANESE
            ENGLISH.containsMatchIn(s) -> AudioLang.ENGLISH
            else -> AudioLang.UNKNOWN
        }
    }
}

/**
 * Selección automática de pista de audio por idioma preferido — la ventaja EXCLUSIVA de Arkiv sobre
 * Alfa/Balandro (que sólo eligen idioma a nivel de FUENTE, no de pista dentro del contenedor). Para
 * contenido DUAL (latino+castellano en el mismo MKV) arranca en la pista correcta sin intervención.
 */
object AudioTrackSelector {
    /** Orden por defecto para audiencia es-LatAm: Latino > Castellano > Dual (coincide con animeLangPriority). */
    val DEFAULT_PREFERENCE = listOf(AudioLang.LATINO, AudioLang.CASTELLANO, AudioLang.DUAL)

    /**
     * Id de pista a seleccionar según [preference], o null si nada coincide (→ dejar la pista por
     * defecto de VLC). Ignora la pseudo-pista "Disable" (id<0) y no hace nada si hay ≤1 pista real.
     * El español genérico (SPANISH) sirve de comodín para las preferencias LATINO/CASTELLANO.
     */
    fun select(tracks: List<Pair<Int, String>>, preference: List<AudioLang> = DEFAULT_PREFERENCE): Int? {
        val real = tracks.filter { it.first >= 0 }
        if (real.size <= 1) return null
        val classed = real.map { it.first to LangTokens.classify(it.second) }
        for (pref in preference) {
            classed.firstOrNull { it.second == pref }?.let { return it.first }
            if (pref == AudioLang.LATINO || pref == AudioLang.CASTELLANO) {
                classed.firstOrNull { it.second == AudioLang.SPANISH }?.let { return it.first }
            }
        }
        return null
    }
}
