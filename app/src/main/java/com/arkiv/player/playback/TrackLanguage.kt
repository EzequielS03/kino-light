package com.arkiv.player.playback

/**
 * Buckets de idioma normalizados. Diccionario robado del `set_lang()` de Alfa (unify.py:504): colapsa
 * las decenas de variantes que aparecen en nombres de pista ("Track 1 - [Spanish]", "Español
 * (Latinoamérica)", "Castellano", "Dual", "es-419"…) a unos pocos buckets. Sirve para audio Y para
 * subtítulos, de ahí el nombre genérico.
 */
enum class TrackLang { LATINO, CASTELLANO, SPANISH, DUAL, ENGLISH, JAPANESE, UNKNOWN }

/** Clasificador de idioma por texto libre. Portado del diccionario de Alfa/Balandro. */
object LangTokens {
    // OJO: `\w` de Java/Kotlin NO matchea `ñ`/acentos → se usan clases explícitas [nñ] y se aceptan las
    // formas sin acento (los releases suelen quitarlos: "Espanol", "Castellano").
    private val LATINO = Regex("latinoameric|\\blatino\\b|\\blat\\b|es-?419|espa[nñ]ol\\s*lat|audio\\s*lat|m[eé]xic")
    private val CASTELLANO = Regex("castellan|castilian|espa[nñ]a\\b|\\bspain\\b|es-?es\\b|\\bcast\\b")
    private val DUAL = Regex("\\bdual\\b|multi[- ]?audio|\\bmulti\\b")
    private val ENGLISH = Regex("\\benglish\\b|\\bingl[eé]s\\b|\\beng\\b|en-?us\\b|en-?gb\\b|\\[en\\]")
    private val JAPANESE = Regex("\\bjapanese\\b|\\bjapon[eé]s\\b|\\bjap\\b|\\bjpn?\\b|\\bvose\\b|\\bvo\\b")
    private val SPANISH = Regex("espa[nñ]ol|\\bspanish\\b|\\bspa\\b|\\besp\\b|castellano|latino|\\[es\\]|\\bes-?\\d*\\b")

    /** Variantes que un hispanohablante entiende por igual: si tenés una en la lista, te sirven todas. */
    private val FAMILIA_ESPANOL = setOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH)

    /**
     * Sufijo de idioma justo antes de la extensión de subtítulo (`movie.es.srt` → `es`). Se busca con
     * la extensión pegada a propósito: aísla un token que es INEQUÍVOCAMENTE un código de idioma, y
     * por eso acá sí se pueden aceptar códigos de dos letras como `en` — buscarlos en texto libre le
     * pegaría a cualquier "Audio EN español".
     */
    // 2..4 letras: cubre `es`, `spa`, `jpn` y también `cast`, que está en CODIGOS.
    private val SUFIJO_ARCHIVO = Regex("\\.([a-z]{2,4}(?:-[a-z0-9]{2,4})?)\\.(?:srt|ass|ssa|sub|vtt)\\b")

    private val CODIGOS = mapOf(
        "es" to TrackLang.SPANISH, "spa" to TrackLang.SPANISH, "esp" to TrackLang.SPANISH,
        "lat" to TrackLang.LATINO, "es-419" to TrackLang.LATINO, "es-mx" to TrackLang.LATINO,
        "cast" to TrackLang.CASTELLANO, "es-es" to TrackLang.CASTELLANO,
        "en" to TrackLang.ENGLISH, "eng" to TrackLang.ENGLISH,
        "ja" to TrackLang.JAPANESE, "jp" to TrackLang.JAPANESE, "jpn" to TrackLang.JAPANESE,
    )

    /** Clasifica un nombre de pista / release al bucket más específico posible. */
    fun classify(raw: String): TrackLang {
        val s = raw.lowercase()
        return when {
            LATINO.containsMatchIn(s) -> TrackLang.LATINO
            CASTELLANO.containsMatchIn(s) -> TrackLang.CASTELLANO
            DUAL.containsMatchIn(s) -> TrackLang.DUAL
            SPANISH.containsMatchIn(s) -> TrackLang.SPANISH
            JAPANESE.containsMatchIn(s) -> TrackLang.JAPANESE
            ENGLISH.containsMatchIn(s) -> TrackLang.ENGLISH
            else -> TrackLang.UNKNOWN
        }
    }

    /**
     * Clasifica una pista EXTERNA por el sufijo de idioma de su nombre de archivo. libVLC nombra las
     * pistas `addSlave` con la ruta, así que sirve para los `.srt` sueltos del torrent. Si no hay
     * sufijo reconocible, cae a [classify] sobre el nombre entero.
     */
    fun classifyFileName(raw: String): TrackLang {
        val s = raw.lowercase()
        SUFIJO_ARCHIVO.find(s)?.groupValues?.get(1)?.let { code -> CODIGOS[code]?.let { return it } }
        return classify(s)
    }

    /**
     * Clasifica un CÓDIGO de idioma suelto (`es`, `es-419`, `jpn`), como el que declaran las fuentes
     * web al lado de la URL del subtítulo. Igual que en [classifyFileName], acá el token viene aislado
     * y por eso sí se acepta un `en` de dos letras. Si no es un código conocido cae a [classify], por
     * si la fuente mandó el nombre escrito ("Español"). Vacío → [TrackLang.UNKNOWN].
     */
    fun classifyCode(raw: String): TrackLang {
        val c = raw.trim().lowercase()
        if (c.isEmpty()) return TrackLang.UNKNOWN
        return CODIGOS[c] ?: classify(c)
    }

    /**
     * ¿[lang] cuenta como "un idioma que entiendo", dada mi lista [order]? Las variantes del español
     * son intercambiables: con `Latino > Castellano` configurado, una pista etiquetada solo "Spanish"
     * tiene que contar como propia — si no, prenderíamos subtítulos sobre un audio que se entiende.
     */
    fun satisfies(lang: TrackLang, order: List<TrackLang>): Boolean =
        lang in order || (lang in FAMILIA_ESPANOL && order.any { it in FAMILIA_ESPANOL })
}

/**
 * Selección de pista por idioma preferido — la ventaja EXCLUSIVA de Arkiv sobre Alfa/Balandro (que
 * sólo eligen idioma a nivel de FUENTE, no de pista dentro del contenedor). Sirve igual para audio y
 * para subtítulos; lo único que cambia es la lista que se le pasa.
 */
object TrackSelector {
    /** Orden por defecto para audiencia es-LatAm (coincide con animeLangPriority). */
    val DEFAULT_AUDIO = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.DUAL)

    /**
     * Id de pista a seleccionar según [order], o null si nada coincide (→ dejar la pista por defecto
     * de VLC). Ignora la pseudo-pista "Disable" (id<0).
     *
     * [requireChoice] = true (audio): con una sola pista real no hay nada que elegir y se devuelve
     * null, para no pelearle a VLC por una decisión que no existe. false (subtítulos): un único
     * subtítulo en japonés SÍ hay que poder prenderlo.
     *
     * [classifier] permite pasar [LangTokens.classifyFileName] para pistas externas.
     */
    fun select(
        tracks: List<Pair<Int, String>>,
        order: List<TrackLang>,
        requireChoice: Boolean = true,
        classifier: (String) -> TrackLang = LangTokens::classify,
    ): Int? {
        val real = tracks.filter { it.first >= 0 }
        if (real.isEmpty()) return null
        if (requireChoice && real.size <= 1) return null
        val classed = real.map { it.first to classifier(it.second) }
        for (pref in order) {
            classed.firstOrNull { it.second == pref }?.let { return it.first }
            // El español genérico es comodín de las preferencias hispanas.
            if (pref == TrackLang.LATINO || pref == TrackLang.CASTELLANO) {
                classed.firstOrNull { it.second == TrackLang.SPANISH }?.let { return it.first }
            }
        }
        return null
    }
}
