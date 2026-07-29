package com.arkiv.player.data.model

/** Una variante concreta de archivo de video dentro de un ítem de archive.org. */
data class VideoVariant(
    val path: String,        // ruta dentro del ítem, ej "carpeta/video.mkv"
    val format: String,      // "Matroska", "h.264", ...
    val sizeBytes: Long,
)

/** Un episodio/video lógico: agrupa el original (mkv) y su derivado (mp4). */
data class Episode(
    val id: String,          // estable: "<identifier>::<claveBase>"
    val itemId: String,      // identifier del ítem
    val section: String,     // carpeta (vacío si está en la raíz)
    val displayName: String, // nombre limpio para mostrar
    val orderIndex: Int,     // orden natural dentro del ítem
    val durationSeconds: Double,
    val thumbPath: String?,  // ruta de miniatura en .thumbs (o null)
    val original: VideoVariant?,   // mejor calidad (mkv) — puede faltar
    val derivative: VideoVariant?, // mp4 h.264 (compatible con Cast) — puede faltar
) {
    /** Variante preferida para reproducir localmente: original si existe. */
    val playbackVariant: VideoVariant?
        get() = original ?: derivative

    /** Variante para Chromecast: siempre el mp4 compatible si existe. */
    val castVariant: VideoVariant?
        get() = derivative ?: original
}

/** Un ítem de archive.org con sus videos ya agrupados. */
data class ArchiveItem(
    val identifier: String,
    val title: String,
    val description: String?,
    val thumbnailUrl: String,
    val episodes: List<Episode>,
)

/** Archivo crudo del JSON de metadata (antes de agrupar). Facilita testear el parser. */
data class RawFile(
    val name: String,
    val source: String,      // "original" | "derivative" | "metadata"
    val format: String,
    val original: String?,   // para derivados: nombre del archivo original
    val sizeBytes: Long,
    val lengthSeconds: Double,
)
