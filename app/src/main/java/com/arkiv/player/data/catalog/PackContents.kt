package com.arkiv.player.data.catalog

import com.arkiv.player.data.MetadataParser
import com.arkiv.player.torrent.PackFileParser
import com.arkiv.player.torrent.TorrentEngine
import com.arkiv.player.torrent.TorrentFile

/** Una fila mostrable del diálogo de pack: un archivo de video con su etiqueta/sección/orden. */
data class PackFileRow(
    val index: Int,        // torrentFileIndex dentro del torrent
    val label: String,     // "T1 · E2" | "Ep 1085" | nombre limpio
    val sizeBytes: Long,
    val quality: String,   // "1080p" | ""
    val section: String,   // "Temporada 1" | ""
    val orderIndex: Int,
)

object PackRowBuilder {
    fun build(files: List<TorrentFile>): List<PackFileRow> = files.mapIndexed { pos, f ->
        val info = PackFileParser.parse(f.name)
        val (label, section, order) = when {
            info.season != null && info.episode != null ->
                Triple("T${info.season} · E${info.episode}", "Temporada ${info.season}", info.season * 1000 + info.episode)
            info.absolute != null ->
                Triple("Ep ${info.absolute}", "", info.absolute)
            else ->
                Triple(MetadataParser.cleanName(f.name), "", pos)
        }
        PackFileRow(f.index, label, f.sizeBytes, QualityLabel.extract(f.name), section, order)
    }.sortedBy { it.orderIndex }
}

/** Resuelve la metadata de una fuente pack y arma sus filas mostrables. Null si no se pudo (sin
 *  seeds/timeout, o sin video). El resolver de magnet puede tardar (hasta ~45s buscando peers). */
class PackResolver(
    private val torrentSearchApi: TorrentSearchApi,
    private val torrentEngine: TorrentEngine,
) {
    data class PackContents(val infoHashHex: String, val infoBytes: ByteArray, val rows: List<PackFileRow>)

    suspend fun resolve(result: TorrentResult): PackContents? {
        val source = torrentSearchApi.resolveSource(result) ?: return null
        val meta = when (source) {
            is TorrentSource.Magnet -> torrentEngine.resolveMagnet(source.uri)
            is TorrentSource.TorrentFile -> torrentEngine.resolveTorrent(source.bytes)
        } ?: return null
        val files = torrentEngine.videoFiles(meta)
        if (files.isEmpty()) return null
        return PackContents(meta.infoHashHex, meta.infoBytes, PackRowBuilder.build(files))
    }
}
