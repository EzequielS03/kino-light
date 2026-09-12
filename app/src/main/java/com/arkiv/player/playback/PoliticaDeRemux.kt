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
}
