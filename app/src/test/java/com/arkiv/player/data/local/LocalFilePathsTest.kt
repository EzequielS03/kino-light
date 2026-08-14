package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LocalFilePathsTest {

    @Test
    fun `sanitiza caracteres que no valen en un nombre de archivo`() {
        assertEquals("web_series_123__s01e02", LocalFilePaths.sanitize("web:series:123::s01e02"))
    }

    @Test
    fun `conserva letras numeros punto guion y guion bajo`() {
        assertEquals("Show.S01E02-1080p_x265", LocalFilePaths.sanitize("Show.S01E02-1080p_x265"))
    }

    @Test
    fun `toma la extension del nombre de origen`() {
        assertEquals("ep1.mkv", LocalFilePaths.fileNameFor("ep1", "Serie S01E01 1080p.mkv"))
    }

    @Test
    fun `cae a mp4 si el origen no tiene extension`() {
        assertEquals("ep1.mp4", LocalFilePaths.fileNameFor("ep1", "sin extension"))
    }

    @Test
    fun `cae a mp4 si no hay nombre de origen`() {
        assertEquals("ep1.mp4", LocalFilePaths.fileNameFor("ep1", null))
    }

    /** Una "extensión" larga es parte del título, no una extensión (ej. "Peli 2024.Latino"). */
    @Test
    fun `ignora una extension implausible y cae a mp4`() {
        assertEquals("ep1.mp4", LocalFilePaths.fileNameFor("ep1", "Peli 2024.Latino"))
    }

    @Test
    fun `normaliza la extension a minusculas`() {
        assertEquals("ep1.mkv", LocalFilePaths.fileNameFor("ep1", "Serie.MKV"))
    }

    /**
     * `m2ts` faltaba en la lista de acá pero no en la de `TorrentEngine`: un Blu-ray remuxeado se
     * podía elegir para bajar y después se guardaba con el nombre cambiado a `.mp4`. Con una sola
     * lista compartida (`ContenedorDeVideo`) esa clase de hueco desaparece.
     */
    @Test
    fun `conserva la extension de los contenedores que sabemos reproducir`() {
        assertEquals("ep1.m2ts", LocalFilePaths.fileNameFor("ep1", "BluRay/00001.m2ts"))
        assertEquals("ep1.ts", LocalFilePaths.fileNameFor("ep1", "ABC_media.ts"))
        assertEquals("ep1.avi", LocalFilePaths.fileNameFor("ep1", "Peli.DivX.avi"))
    }

    /**
     * A la descarga de la NUC le llega una URL de PÁGINA como nombre de origen. Cortar por el
     * último punto sobre eso devuelve basura (`sitio.com/peli` → `"com/peli"`), así que el nombre
     * tiene que normalizarse como URL antes de mirarle la extensión.
     */
    @Test
    fun `una url de pagina no aporta extension y cae a mp4`() {
        assertEquals("ep1.mp4", LocalFilePaths.fileNameFor("ep1", "https://allcalidad.com/peli-x/"))
        assertEquals("ep1.mp4", LocalFilePaths.fileNameFor("ep1", "https://sitio.com/ver/peli"))
    }

    @Test
    fun `una url con archivo de video si aporta extension`() {
        assertEquals("ep1.mkv", LocalFilePaths.fileNameFor("ep1", "https://cdn.com/a/b.mkv?token=x"))
    }

    @Test
    fun `el parcial agrega punto part`() {
        assertEquals("ep1.mkv.part", LocalFilePaths.partOf(File("/tmp/ep1.mkv")).name)
    }

    @Test
    fun `el directorio de torrent usa el episodeId sanitizado`() {
        assertEquals("web_series_9__s01e01", LocalFilePaths.torrentDirName("web:series:9::s01e01"))
    }
}
