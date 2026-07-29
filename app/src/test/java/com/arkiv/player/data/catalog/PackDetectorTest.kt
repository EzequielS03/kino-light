package com.arkiv.player.data.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackDetectorTest {

    @Test fun detectsSeasonPacks() {
        assertTrue(PackDetector.isPack("The Simpsons Season 5 COMPLETE 1080p"))
        assertTrue(PackDetector.isPack("Los Simpson Temporada 5 [1080p]"))
        assertTrue(PackDetector.isPack("Breaking Bad S01-S05 1080p BluRay"))
        assertTrue(PackDetector.isPack("Show.S03.1080p.WEB-DL"))
    }

    @Test fun detectsEpisodeRanges() {
        assertTrue(PackDetector.isPack("One Piece Cap.301_310 HDTV"))
        assertTrue(PackDetector.isPack("Show S01E01-E10 1080p"))
    }

    @Test fun detectsCollections() {
        assertTrue(PackDetector.isPack("Harry Potter Coleccion Completa 1080p"))
        assertTrue(PackDetector.isPack("Star Wars Complete Collection"))
    }

    @Test fun singleEpisodeIsNeverAPack() {
        // El falso positivo que hace inservible el isPack interno para un badge visible.
        assertFalse(PackDetector.isPack("Show Season 3 Episode 4 1080p"))
        assertFalse(PackDetector.isPack("The.Simpsons.S05E12.1080p.WEB-DL"))
        assertFalse(PackDetector.isPack("Los Simpson 5x12 Castellano"))
        assertFalse(PackDetector.isPack("One Piece Cap.305 HDTV"))
    }

    @Test fun plainMovieIsNotAPack() {
        assertFalse(PackDetector.isPack("Dune.2021.1080p.WEB-DL.x264"))
        assertFalse(PackDetector.isPack("[SubsPlease] One Piece - 1085 (1080p).mkv"))
    }

    @Test fun movieWithCompleteInNameIsNotAPack() {
        // Falso positivo real detectado probando en el S24+: "COMPLETE" en una peli = disco BluRay
        // entero, no varios episodios.
        assertFalse(PackDetector.isPack("Deadpool.2016.COMPLETE.2160p.BluRay.REMUX.DV.HDR10+.MULTi"))
    }
}
