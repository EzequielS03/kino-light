package com.arkiv.player.data.local

import com.arkiv.player.playback.PlayerSource
import com.arkiv.player.playback.SourceKind

/**
 * La `source` de la tabla `downloads` que le corresponde a un episodio, o sea qué estrategia de
 * [AppGraph.downloadStrategies][com.arkiv.player.AppGraph] lo sabe bajar.
 *
 * Se deriva del prefijo del id con el MISMO [PlayerSource.kindFor] que usa el reproductor, para no
 * inventar una segunda forma de decidir de dónde vino un episodio.
 *
 * El `when` es exhaustivo a propósito: el bug que motivó extraer esto fue un `else -> "archive"`
 * que se tragó los capítulos de Magis, que entonces caían en la estrategia de archive.org y morían
 * con "Este episodio no tiene un archivo descargable". Con la lista completa, una fuente nueva no
 * compila hasta que alguien decida quién la baja.
 */
object FuenteDeDescarga {
    fun para(episodeId: String): String = when (PlayerSource.kindFor(episodeId)) {
        SourceKind.MAGIS -> "magis"
        // Caracol no se baja: su video viene cifrado con Widevine. "ditu" no tiene estrategia en
        // `AppGraph.downloadStrategies`, así que [sePuedeBajar] no lo ofrece (y una fila que llegara
        // igual a la cola, `LocalDownloadWorker` la marca FAILED con "Fuente no soportada: ditu").
        // Mandarlo a "archive" diría que es de archive.org, que no lo es.
        SourceKind.DITU -> "ditu"
        // NUC/LOCAL/LIVE no salen de `kindFor`, y un canal en vivo no se baja; archive.org es el
        // caso restante (un identifier pelado, sin prefijo).
        SourceKind.ARCHIVE, SourceKind.NUC, SourceKind.LOCAL, SourceKind.LIVE -> "archive"
    }

    /**
     * Si a la persona se le puede ofrecer bajar este episodio: hay una estrategia registrada para su
     * fuente. [estrategias] son las claves de `AppGraph.downloadStrategies`.
     *
     * Existe para no mostrar una opción que va a fallar: sin estrategia, `LocalDownloadWorker` marca
     * la fila FAILED con "Fuente no soportada", DESPUÉS de que la pantalla ya dijo "Guardando". Se
     * decide por la estrategia y no por el nombre de la fuente: una fuente nueva sin estrategia queda
     * escondida sola.
     */
    fun sePuedeBajar(episodeId: String, estrategias: Set<String>): Boolean =
        hayEstrategia(para(episodeId), estrategias)

    /** Lo mismo, con la fuente ya en la mano (`items.source`, que es lo que tiene la biblioteca). */
    fun hayEstrategia(fuente: String, estrategias: Set<String>): Boolean = fuente in estrategias
}
