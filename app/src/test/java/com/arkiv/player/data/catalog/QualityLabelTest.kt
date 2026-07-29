package com.arkiv.player.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class QualityLabelTest {

    @Test fun resolutionAndSource() {
        assertEquals("1080p BluRay", QualityLabel.extract("The.Matrix.1999.1080p.BluRay.x264-GROUP.mkv"))
        assertEquals("720p WEB", QualityLabel.extract("Show.S01E01.720p.WEB-DL.mkv"))
        assertEquals("4K REMUX", QualityLabel.extract("Movie.2160p.UHD.BluRay.REMUX.HEVC.mkv"))
        assertEquals("1080p HDRip", QualityLabel.extract("Pelicula.2020.1080p.HDRip.Latino.mkv"))
    }

    @Test fun hdrAndDolbyVision() {
        assertEquals("4K HDR", QualityLabel.extract("Movie.2160p.HDR10.mkv"))
        assertEquals("4K REMUX DV", QualityLabel.extract("Movie.4K.REMUX.Dolby.Vision.mkv"))
    }

    @Test fun lowQualitySources() {
        assertEquals("CAM", QualityLabel.extract("Nueva.Pelicula.2024.HDTS.Telesync.mkv"))
        assertEquals("1080p CAM", QualityLabel.extract("Supergirl.2026.1080p.Telesync.h265.mkv"))
    }

    @Test fun spanishSceneTokens() {
        // Tokens hispanos pegados (Balandro) que antes se escapaban.
        assertEquals("1080p MicroHD", QualityLabel.extract("Pelicula.2020.MicroHD-1080p.Castellano.mkv"))
        assertEquals("4K REMUX", QualityLabel.extract("Pelicula.4KUHDReMux.2160p.mkv"))
        assertEquals("4K HDR", QualityLabel.extract("Pelicula.4KHDR.HDR10.mkv"))
        assertEquals("1080p REMUX", QualityLabel.extract("Movie.BDRemux.1080p.mkv"))
        assertEquals("4K", QualityLabel.extract("Movie.4KUHD.mkv"))
    }

    @Test fun emptyWhenNoTokens() {
        assertEquals("", QualityLabel.extract("Some.Movie.Name.x264.mkv"))
    }
}
