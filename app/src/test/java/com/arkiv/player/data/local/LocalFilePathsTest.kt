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

    @Test
    fun `el parcial agrega punto part`() {
        assertEquals("ep1.mkv.part", LocalFilePaths.partOf(File("/tmp/ep1.mkv")).name)
    }

    @Test
    fun `el directorio de torrent usa el episodeId sanitizado`() {
        assertEquals("web_series_9__s01e01", LocalFilePaths.torrentDirName("web:series:9::s01e01"))
    }
}
