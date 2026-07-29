package com.arkiv.player.data

import com.arkiv.player.data.model.ArchiveItem
import com.arkiv.player.data.model.Episode
import com.arkiv.player.data.model.RawFile
import com.arkiv.player.data.model.VideoVariant

/**
 * Convierte la lista cruda de archivos de un ítem de archive.org en episodios
 * agrupados. Puro (sin dependencias de Android) para poder testearlo en la JVM.
 */
object MetadataParser {

    private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "webm", "m4v", "ogv", "mov")

    fun parse(
        identifier: String,
        title: String,
        description: String?,
        thumbnailUrl: String,
        files: List<RawFile>,
    ): ArchiveItem {
        val videos = files.filter { it.isVideo() }
        val thumbs = files.filter { it.format.equals("Thumbnail", ignoreCase = true) }

        // Agrupar por "contenido": el derivado apunta a su original vía `original`.
        val groups = videos.groupBy { groupKey(it) }

        val episodes = groups.map { (key, groupFiles) ->
            val originalFile = groupFiles.firstOrNull { it.source == "original" }
            val derivativeFile = groupFiles.firstOrNull { it.source == "derivative" }
                ?: groupFiles.firstOrNull { it != originalFile }
            val reference = originalFile ?: derivativeFile!!

            val duration = groupFiles.map { it.lengthSeconds }.maxOrNull() ?: 0.0
            val thumb = thumbs.firstOrNull { it.original == reference.name }?.name

            Episode(
                id = "$identifier::$key",
                itemId = identifier,
                section = directoryOf(reference.name),
                displayName = cleanName(reference.name),
                orderIndex = 0, // se completa tras ordenar
                durationSeconds = duration,
                thumbPath = thumb,
                original = originalFile?.toVariant(),
                derivative = derivativeFile?.toVariant(),
            )
        }.sortedWith(episodeOrder).mapIndexed { index, ep -> ep.copy(orderIndex = index) }

        return ArchiveItem(
            identifier = identifier,
            title = title,
            description = description,
            thumbnailUrl = thumbnailUrl,
            episodes = episodes,
        )
    }

    private fun RawFile.isVideo(): Boolean {
        if (format.equals("Thumbnail", ignoreCase = true)) return false
        return extensionOf(name) in VIDEO_EXTENSIONS
    }

    private fun RawFile.toVariant() = VideoVariant(path = name, format = format, sizeBytes = sizeBytes)

    /** Clave de agrupamiento: el original real (sin extensión), para unir mkv+mp4. */
    private fun groupKey(file: RawFile): String {
        val base = if (!file.original.isNullOrBlank()) file.original else file.name
        return stripExtension(base)
    }

    private val episodeOrder = Comparator<Episode> { a, b ->
        val bySection = naturalCompare(a.section, b.section)
        if (bySection != 0) bySection else naturalCompare(a.displayName, b.displayName)
    }

    // --- helpers de nombres ---

    fun extensionOf(path: String): String =
        path.substringAfterLast('.', "").lowercase()

    fun stripExtension(path: String): String {
        val dot = path.lastIndexOf('.')
        val slash = path.lastIndexOf('/')
        return if (dot > slash && dot >= 0) path.substring(0, dot) else path
    }

    fun directoryOf(path: String): String {
        val slash = path.lastIndexOf('/')
        return if (slash >= 0) path.substring(0, slash) else ""
    }

    /** Nombre para mostrar: sin carpeta ni extensión, con separadores legibles. */
    fun cleanName(path: String): String {
        val base = stripExtension(path.substringAfterLast('/'))
        return base.replace('_', ' ').replace("@", " · ").trim()
    }

    /** Orden natural: compara tramos de dígitos numéricamente (E2 < E10). */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ni = i
                var nj = j
                while (ni < a.length && a[ni].isDigit()) ni++
                while (nj < b.length && b[nj].isDigit()) nj++
                val na = a.substring(i, ni).trimStart('0').ifEmpty { "0" }
                val nb = b.substring(j, nj).trimStart('0').ifEmpty { "0" }
                if (na.length != nb.length) return na.length - nb.length
                val cmp = na.compareTo(nb)
                if (cmp != 0) return cmp
                i = ni
                j = nj
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
