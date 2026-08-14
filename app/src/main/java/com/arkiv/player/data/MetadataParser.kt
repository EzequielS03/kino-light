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
            val number = episodeNumberOf(reference.name)

            Episode(
                id = "$identifier::$key",
                itemId = identifier,
                section = directoryOf(reference.name),
                displayName = cleanName(reference.name, identifier),
                orderIndex = 0, // se completa tras ordenar
                durationSeconds = duration,
                thumbPath = thumb,
                original = originalFile?.toVariant(),
                derivative = derivativeFile?.toVariant(),
                season = number?.first,
                episode = number?.second,
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
        return com.arkiv.player.playback.ContenedorDeVideo.esVideo(name)
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

    /**
     * Nombre para mostrar: sin carpeta ni extensión, con separadores legibles.
     *
     * Si el archivo empieza con el [identifier] del ítem, ese prefijo se saca: archive.org
     * nombra así los archivos de muchas subidas (incluidas las nuestras, donde el identificador
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

    /**
     * (temporada, capítulo) sacados del nombre del archivo, o null si no hay patrón claro.
     *
     * Hace falta para poder pedirle a TMDB el título real del capítulo: ni archive.org ni el
     * mirror guardan el nombre del episodio, solo su número. Se parsea del nombre —en vez de
     * pedírselo al mirror— para que funcione igual con ítems públicos que nunca pasaron por
     * nosotros.
     *
     * Ante la duda devuelve null: inventar un número haría que la UI muestre el título de OTRO
     * capítulo, que es peor que mostrar el nombre del archivo.
     */
    fun episodeNumberOf(name: String): Pair<Int, Int>? {
        val base = stripExtension(name.substringAfterLast('/'))
        SXEX.find(base)?.let { m ->
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }
        // "1920x1080" también matchea NxNN: si el número de capítulo tiene pinta de alto de
        // video, es una resolución, no un episodio.
        NXNN.find(base)?.let { m ->
            val season = m.groupValues[1].toInt()
            val episode = m.groupValues[2].toInt()
            if (season <= MAX_SEASON) return season to episode
        }
        return null
    }

    /** Más allá de esto no es una temporada: es el ancho de una resolución (1920x1080). */
    private const val MAX_SEASON = 100
    private val SXEX = Regex("""(?<![a-z0-9])s(\d{1,3})e(\d{1,4})(?![0-9])""", RegexOption.IGNORE_CASE)
    private val NXNN = Regex("""(?<![a-z0-9.])(\d{1,3})x(\d{1,4})(?![0-9])""", RegexOption.IGNORE_CASE)

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
