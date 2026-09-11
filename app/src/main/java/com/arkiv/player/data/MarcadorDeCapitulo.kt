package com.arkiv.player.data

import com.arkiv.player.data.db.SkipMarkerEntity

/**
 * Qué marcador de intro/outro le toca a un capítulo.
 *
 * Los tiempos son POR CAPÍTULO y no por serie: medido contra AniSkip, en Demon Slayer el opening
 * del capítulo 1 arranca a los 1270 s y el del 2 a los 57 s (cold opens largos, recapitulaciones,
 * especiales). Un solo marcador por serie mandaría el salto a la mitad del capítulo.
 *
 * El marcador de serie (`episodeId` vacío) NO desaparece: es el que se pone a mano, y sigue
 * valiendo para los capítulos que no tienen uno propio.
 *
 * **Origen y precedencia.** AniSkip a veces se equivoca (medido contra el aparato real: un
 * capítulo trajo los créditos etiquetados como opening) y no hay forma fiable de detectarlo. La
 * salida es que la persona lo corrija a mano -- así que lo MANUAL manda sobre lo automático **sin
 * importar el alcance**, y solo a igualdad de origen decide el alcance (capítulo > serie). Si el
 * automático de capítulo le ganara siempre a lo manual, un tiempo mal etiquetado no se podría
 * arreglar nunca.
 */
object MarcadorDeCapitulo {

    /** Lo que ya existe y lo que pone una persona a mano: vale hasta que algo manual lo corrija. */
    const val ORIGEN_MANUAL = "manual"

    /** Lo que trae AniSkip sin que nadie lo haya tocado: cede ante cualquier corrección manual. */
    const val ORIGEN_AUTO = "auto"

    /**
     * The row's key. Derived and not composite on purpose: this branch's future sync (see the
     * `updatedAt`/`deleted` columns kept for it) looks up the remote row by ONE natural field per
     * collection, so a composite key would force changing that mechanism for all of them. Same
     * criterion as `episodes`, which syncs by `epId`.
     */
    fun idDe(itemId: String, episodeId: String): String = "$itemId|$episodeId"

    /**
     * El que manda, en orden: manual-de-capítulo, manual-de-serie, auto-de-capítulo,
     * auto-de-serie. Lo manual le gana a lo automático sin importar el alcance; a igualdad de
     * origen, el capítulo le gana a la serie. Un marcador sin ningún tiempo no cuenta.
     */
    fun elegir(delCapitulo: SkipMarkerEntity?, deLaSerie: SkipMarkerEntity?): SkipMarkerEntity? {
        val capitulo = delCapitulo?.takeIf { it.tieneTiempos }
        val serie = deLaSerie?.takeIf { it.tieneTiempos }
        val manualCapitulo = capitulo?.takeIf { it.origen == ORIGEN_MANUAL }
        val manualSerie = serie?.takeIf { it.origen == ORIGEN_MANUAL }
        return manualCapitulo ?: manualSerie ?: capitulo ?: serie
    }

    /**
     * ¿[posicionMs] cae dentro del opening de [marcador]? Sin `openingEndMs` no hay opening que
     * marcar (un marcador puede traer solo el ending). Extraída de PlayerScreen para poder
     * probarla sin Compose -- es la cuenta que decide si sale el botón "Saltar intro".
     */
    fun enOpening(marcador: SkipMarkerEntity?, posicionMs: Long): Boolean {
        val fin = marcador?.openingEndMs ?: return false
        return posicionMs in (marcador.openingStartMs ?: 0L)..fin
    }

    /**
     * ¿[posicionMs] ya entró al ending de [marcador]? Sin `endingStartMs` no hay ending que
     * marcar. Mismo motivo que [enOpening]: la cuenta que decide "Saltar outro".
     */
    fun enEnding(marcador: SkipMarkerEntity?, posicionMs: Long): Boolean {
        val inicio = marcador?.endingStartMs ?: return false
        return posicionMs >= inicio
    }

    private val SkipMarkerEntity.tieneTiempos: Boolean
        get() = openingEndMs != null || endingStartMs != null
}
