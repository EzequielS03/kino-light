package com.arkiv.player.data

import android.net.Uri

/** Construye URLs de archive.org con encoding correcto de cada segmento del path. */
object ArchiveUrls {

    private const val BASE = "https://archive.org"

    fun metadata(identifier: String): String = "$BASE/metadata/$identifier"

    fun thumbnail(identifier: String): String = "$BASE/services/img/$identifier"

    /**
     * URL de descarga/streaming de un archivo dentro de un ítem. Cada segmento
     * del path se codifica por separado (los nombres traen '@', espacios, etc.),
     * pero se conservan las barras que separan carpetas.
     */
    fun download(identifier: String, path: String): String {
        val encoded = path.split('/').joinToString("/") { segment ->
            Uri.encode(segment)
        }
        return "$BASE/download/$identifier/$encoded"
    }
}
