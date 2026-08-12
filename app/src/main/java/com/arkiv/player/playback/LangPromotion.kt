package com.arkiv.player.playback

/**
 * Elegir una pista a mano en el player sube ese idioma al tope de la preferencia — pero SOLO si hubo
 * una elección real. Sin esta mitigación, una película que venía únicamente en inglés te dejaría el
 * inglés arriba para siempre y la próxima película dual arrancaría en inglés sin que lo pidieras.
 */
object LangPromotion {

    /** Nuevo orden con el idioma elegido al tope, o null si no corresponde promover. */
    fun promote(
        order: List<TrackLang>,
        pickedName: String,
        allNames: List<String>,
        classifier: (String) -> TrackLang = LangTokens::classify,
    ): List<TrackLang>? {
        val picked = classifier(pickedName)
        if (picked == TrackLang.UNKNOWN) return null
        // ¿Había alternativa? Con un solo idioma en el archivo, elegirlo no es una preferencia.
        if (allNames.map(classifier).distinct().size < 2) return null
        if (order.firstOrNull() == picked) return null
        return listOf(picked) + order.filter { it != picked }
    }
}

/**
 * Ediciones de la lista ordenada de idiomas. Vive acá, puro y testeado, porque el celular
 * (`material3`) y el TV (`androidx.tv.material3`) no pueden compartir composables pero sí tienen que
 * comportarse igual.
 */
object LangOrderEdits {

    /** Agrega al final si no está; lo saca si está. Nunca deja la lista vacía. */
    fun toggle(order: List<TrackLang>, lang: TrackLang): List<TrackLang> = when {
        lang !in order -> order + lang
        order.size <= 1 -> order
        else -> order - lang
    }

    fun moveUp(order: List<TrackLang>, lang: TrackLang): List<TrackLang> = swap(order, lang, -1)

    fun moveDown(order: List<TrackLang>, lang: TrackLang): List<TrackLang> = swap(order, lang, +1)

    private fun swap(order: List<TrackLang>, lang: TrackLang, delta: Int): List<TrackLang> {
        val i = order.indexOf(lang)
        val j = i + delta
        if (i < 0 || j !in order.indices) return order
        return order.toMutableList().apply { this[i] = this[j].also { this[j] = this[i] } }
    }
}
