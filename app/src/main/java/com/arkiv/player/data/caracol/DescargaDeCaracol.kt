package com.arkiv.player.data.caracol

import org.json.JSONArray
import org.json.JSONObject

/**
 * Lo que hay que recordar de un capítulo de Caracol bajado al dispositivo.
 *
 * No alcanza con "está en disco". Una descarga de Caracol no es un archivo: son los segmentos
 * CIFRADOS dentro del caché de media3, y para volver a abrirlos hacen falta dos datos que no se
 * pueden adivinar después:
 *
 *  - [mpd]: la URL con la que se llenó el caché. Un caché se indexa por la URI con la que se
 *    escribió, así que reproducir con otra —aunque apunte al mismo video— falla TODOS los bytes y
 *    se va a la red en silencio. Resolver de nuevo no garantiza la misma URL.
 *  - [claves]: qué calidad se bajó. El manifiesto sigue anunciando las seis; sin este filtro el
 *    selector elige por ancho de banda y pide una que no está. Ver [CalidadDeCaracol].
 *
 * Lo que NO guarda es la llave del video: no la tenemos ni la podemos tener. Los bytes en disco
 * están cifrados igual que en el CDN (`encv`/`sinf`/`tenc`) y quien los descifra es el CDM del
 * aparato, al darle play, pidiendo una licencia de streaming fresca. Por eso esta "descarga"
 * necesita unos KB de red para abrir: el servidor de licencias de Caracol no concede licencias
 * persistentes (medido, ver `SondaDeCaracolOffline` en `src/debug`).
 */
data class DescargaDeCaracol(
    val mpd: String,
    /** `período.grupo.pista` de cada pista bajada, en el orden en que se bajaron. */
    val claves: List<ClaveDePista>,
    /** Alto en píxeles del video bajado; 0 si no se supo. Es para MOSTRARLO, no para decidir. */
    val alto: Int = 0,
) {
    fun aJson(): String = JSONObject().apply {
        put(CAMPO_MPD, mpd)
        put(CAMPO_ALTO, alto)
        put(CAMPO_CLAVES, JSONArray().apply { claves.forEach { put(it.texto()) } })
    }.toString()

    companion object {
        private const val CAMPO_MPD = "mpd"
        private const val CAMPO_ALTO = "alto"
        private const val CAMPO_CLAVES = "claves"

        /** Extensión del archivito que acompaña a la descarga. Ver `DituDownloadStrategy`. */
        const val EXTENSION = "ditu.json"

        /** `null` si el texto no es un registro entendible: sin él la descarga no se puede abrir. */
        fun deJson(texto: String): DescargaDeCaracol? {
            val json = runCatching { JSONObject(texto) }.getOrNull() ?: return null
            val mpd = json.optString(CAMPO_MPD).takeIf { it.isNotBlank() } ?: return null
            val arr = json.optJSONArray(CAMPO_CLAVES) ?: return null
            val claves = (0 until arr.length()).mapNotNull { ClaveDePista.deTexto(arr.optString(it)) }
            if (claves.isEmpty()) return null
            return DescargaDeCaracol(mpd = mpd, claves = claves, alto = json.optInt(CAMPO_ALTO, 0))
        }
    }
}

/** Un `StreamKey` de DASH sin depender de media3, para poder guardarlo y probarlo. */
data class ClaveDePista(val periodo: Int, val grupo: Int, val pista: Int) {
    fun texto(): String = "$periodo.$grupo.$pista"

    companion object {
        fun deTexto(texto: String): ClaveDePista? {
            val partes = texto.trim().split('.')
            if (partes.size != 3) return null
            val nums = partes.map { it.toIntOrNull() ?: return null }
            if (nums.any { it < 0 }) return null
            return ClaveDePista(nums[0], nums[1], nums[2])
        }
    }
}
