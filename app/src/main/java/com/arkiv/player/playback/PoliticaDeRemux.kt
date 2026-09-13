package com.arkiv.player.playback

/**
 * Decides whether a title has to be remuxed before the Cast receiver will take it, and where the
 * result lives.
 *
 * Separated from the remuxing itself so the DECISION can be pinned by tests on the JVM: the
 * remuxer needs Android (media3's Transformer), the policy does not, and the policy is where the
 * costly mistakes are -- remuxing something that did not need it wastes minutes and a gigabyte,
 * and skipping something that did sends the TV a container it refuses.
 *
 * Why remux at all: the receiver refuses a bare MPEG-TS served progressively, and when the same
 * bytes are handed to it as HLS segments it still has to derive every frame's presentation time
 * from PTS/DTS -- which on the KALLEY produced hundreds of `Failed to get frame timestamps` a
 * minute. An MP4 carries explicit per-sample timing instead. Nothing is re-encoded: Transformer
 * copies the compressed samples when the format already fits.
 */
object PoliticaDeRemux {

    /** Extension of the remuxed copy. Not `.mp4` by accident: it IS an mp4. */
    const val EXTENSION = "mp4"

    /** Remuxed copies live here, under the app's own cache dir. */
    const val CARPETA = "remux"

    /**
     * Does [mime] need remuxing before this receiver will play it properly?
     *
     * Only MPEG-TS does. An mp4 or a webm is what the receiver already wants, and a remux of one
     * would burn time and disk to produce the same thing. Anything unknown is left alone as well:
     * the segmenter path still exists for it, and a remux that fails is worse than a cast that
     * works imperfectly.
     */
    fun hayQueRemuxear(mime: String?): Boolean = mime == Contenedor.MPEGTS.mime

    /**
     * Name of the remuxed copy for [claveDeOrigen].
     *
     * Keyed by the ORIGIN, not by the title: two episodes can share a title, and the same episode
     * re-resolved gets a new proxy url with new tokens but the same object in the CDN. Hashing
     * also keeps the auth blob in the query from ever reaching the filesystem.
     */
    fun nombreDeArchivo(claveDeOrigen: String): String =
        "${claveDeOrigen.hashCode().toUInt().toString(16)}.$EXTENSION"

    /**
     * Is [bytesRemuxeados] far enough ahead of playback at [posicionMs] to start casting?
     *
     * A remux that has only just begun is not castable: the receiver would drain it in seconds and
     * stall. [ARRANQUE_MINIMO_SEG] of content, estimated from the title's own bitrate, is the
     * floor -- enough that the remux, which runs much faster than real time, stays ahead.
     */
    fun sePuedeEmpezar(
        bytesRemuxeados: Long,
        bytesTotales: Long,
        duracionMs: Long,
        posicionMs: Long = 0L,
    ): Boolean {
        if (bytesRemuxeados <= 0L || bytesTotales <= 0L || duracionMs <= 0L) return false
        val segundosListos = duracionMs / 1000.0 * bytesRemuxeados / bytesTotales
        return segundosListos >= posicionMs / 1000.0 + ARRANQUE_MINIMO_SEG
    }

    /** How much finished content must exist before handing the receiver the url. */
    const val ARRANQUE_MINIMO_SEG = 30.0

    /**
     * How long each chunk runs when a title is cast as a QUEUE of finished files.
     *
     * Thirty seconds: short enough that playback starts almost at once (only the first chunk has
     * to exist), long enough that the joins between chunks stay rare. Each chunk is a complete mp4
     * with a duration of its own, which is the entire point -- one file that kept growing made the
     * receiver recompute its duration every second or two and chase an end that never stopped
     * moving.
     */
    const val TROZO_SEG = 30L

    /** How many chunks a title of [duracionMs] is cut into. */
    fun trozosDe(duracionMs: Long): Int =
        if (duracionMs <= 0L) 0 else Math.ceil(duracionMs / 1000.0 / TROZO_SEG).toInt()

    /**
     * Cache key for a remux that starts at [desdeMs] instead of at the beginning.
     *
     * Starting somewhere other than zero is done by remuxing FROM that point, not by seeking into
     * the result. The remux is cast as a LIVE stream -- that is what stopped the receiver inventing
     * an end and stalling against it -- and a live stream has no timeline to seek along. A file
     * that begins where you left off needs none: it plays from its own zero.
     *
     * Rounded to [GRANO_SEG] so reopening a title seconds later reuses the remux instead of paying
     * for another. The rounding goes BACKWARDS on purpose: starting a few seconds early is
     * harmless, starting late skips content.
     */
    fun claveDesde(claveDeOrigen: String, desdeMs: Long): String {
        if (desdeMs <= 0L) return claveDeOrigen
        // EXACT milliseconds, no rounding. Rounding here was a real bug: the keyframe is found to
        // the millisecond and then this filed it under the nearest 30 s, so the remux was clipped
        // 583 ms away from the keyframe and the tracks went back to starting at different instants
        // -- the very desync the search exists to remove. Reuse comes from rounding the REQUEST
        // before the search instead, which lands on the same keyframe and so the same key.
        return "$claveDeOrigen#$desdeMs"
    }

    /** Rounds a resume point down to [GRANO_SEG], so nearby ones look for the same keyframe. */
    fun redondearPeticion(desdeMs: Long): Long =
        if (desdeMs < GRANO_SEG * 1000L) 0L else desdeMs / 1000 / GRANO_SEG * GRANO_SEG * 1000L

    /** How coarsely a resume point is rounded when keying a remux. */
    const val GRANO_SEG = 30L

    /** Where a remux keyed by [claveDesde] actually begins, in ms. */
    fun desdeDeLaClave(clave: String): Long =
        clave.substringAfterLast('#', "").toLongOrNull() ?: 0L

    /** Cache name for chunk [indice] of [claveDeOrigen]. */
    fun nombreDeTrozo(claveDeOrigen: String, indice: Int): String =
        "${claveDeOrigen.hashCode().toUInt().toString(16)}-$indice.$EXTENSION"

    /**
     * Seconds of playable content in [bytes], for a title of [bytesTotales] and [duracionMs].
     *
     * The head start has to be measured in TIME, not megabytes. A fixed 6 MB is twenty-seven
     * seconds of a 221 KB/s title and six seconds of a 1 MB/s one -- the same number meaning
     * "comfortable" for one title and "about to stall" for another.
     */
    fun segundosListos(bytes: Long, bytesTotales: Long, duracionMs: Long): Double {
        if (bytes <= 0L || bytesTotales <= 0L || duracionMs <= 0L) return 0.0
        return duracionMs / 1000.0 * bytes / bytesTotales
    }

    /**
     * Ceiling for everything remuxed, together. A two-hour title is around a gigabyte, and these
     * are derived copies of things the person can always fetch again -- filling their phone with
     * them would be a poor trade for saving a few minutes of re-muxing.
     */
    const val TOPE_BYTES = 4L * 1024 * 1024 * 1024

    /**
     * Which files to drop, oldest first, so that what remains fits under [TOPE_BYTES] alongside
     * [bytesEntrantes].
     *
     * Takes (name, size, lastModified) and returns the names to delete. Pure so the eviction order
     * can be pinned by test: getting it backwards would throw away what is being watched right now
     * and keep what nobody has opened in weeks.
     */
    fun aBorrar(
        archivos: List<Triple<String, Long, Long>>,
        bytesEntrantes: Long = 0L,
    ): List<String> {
        val total = archivos.sumOf { it.second } + bytesEntrantes
        if (total <= TOPE_BYTES) return emptyList()
        var sobra = total - TOPE_BYTES
        val fuera = ArrayList<String>()
        archivos.sortedBy { it.third }.forEach { (nombre, bytes, _) ->
            if (sobra <= 0L) return fuera
            fuera.add(nombre)
            sobra -= bytes
        }
        return fuera
    }
}
