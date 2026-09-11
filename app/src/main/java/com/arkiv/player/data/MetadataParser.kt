package com.arkiv.player.data

/**
 * Cleans up a raw file/title name for display, for Magis and Caracol items
 * (`MagisEntities`, `DituEntities`). Pure (no Android dependencies) so it can be tested on the
 * JVM.
 */
object MetadataParser {

    fun stripExtension(path: String): String {
        val dot = path.lastIndexOf('.')
        val slash = path.lastIndexOf('/')
        return if (dot > slash && dot >= 0) path.substring(0, dot) else path
    }

    /**
     * Nombre para mostrar: sin carpeta ni extensión, con separadores legibles.
     *
     * Si el archivo empieza con el [identifier] del ítem, ese prefijo se saca: archive.org
     * nombraba así los archivos de muchas subidas (incluidas las nuestras, donde el identificador
     * es un hash), y sin pelarlo cada capítulo se vería como
     * "f75163f026d99259e37c 12697 s01e01" en vez de "s01e01".
     */
    fun cleanName(path: String, identifier: String? = null): String {
        val file = path.substringAfterLast('/')
        val base = stripExtension(file)
        val stripped = identifier
            ?.takeIf { it.isNotBlank() && base.length > it.length + 1 }
            ?.let { id -> base.removePrefix("${id}_").takeIf { it != base } }
            ?: base
        return stripped.replace('_', ' ').replace("@", " · ").trim()
    }
}
