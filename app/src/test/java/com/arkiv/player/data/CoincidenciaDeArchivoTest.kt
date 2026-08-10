package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reencontrar un archivo de archive.org después de que le cambiaron el nombre.
 * Ver [CoincidenciaDeArchivo] para el porqué y, sobre todo, para por qué se rinde tan fácil.
 */
class CoincidenciaDeArchivoTest {

    @Test fun si_esta_igual_lo_encuentra() {
        val hay = listOf("Get Backers 19.mp4", "Get Backers 20.mp4")
        assertEquals("Get Backers 19.mp4", CoincidenciaDeArchivo.mejor("Get Backers 19.mp4", hay))
    }

    @Test fun sobrevive_a_un_cambio_de_extension() {
        // archive re-derivó: el mkv original pasó a servirse como mp4.
        val hay = listOf("Serie/Cap 03.mp4")
        assertEquals("Serie/Cap 03.mp4", CoincidenciaDeArchivo.mejor("Serie/Cap 03.mkv", hay))
    }

    // ─── renombres cosméticos, que son la mayoría ──────────────────────────

    @Test fun sobrevive_a_los_guiones_bajos() {
        // Caso real del ítem de Evangelion: los nombres vienen con '_' y '@'.
        val hay = listOf("Evangelion Spanish dubs/TPO Neon Genesis Evangelion 02 Trapo2019.mp4")
        assertEquals(
            "Evangelion Spanish dubs/TPO Neon Genesis Evangelion 02 Trapo2019.mp4",
            CoincidenciaDeArchivo.mejor(
                "Evangelion Spanish dubs/TPO_Neon_Genesis_Evangelion_02_@Trapo2019.mkv", hay,
            ),
        )
    }

    @Test fun sobrevive_a_la_puntuacion() {
        // Caso real de Get Backers: comas, puntos suspensivos y puntos en el nombre.
        val hay = listOf("Get Backers 19 - Oh Mi Amigo Kazuki vs Juubei.mp4")
        assertEquals(
            "Get Backers 19 - Oh Mi Amigo Kazuki vs Juubei.mp4",
            CoincidenciaDeArchivo.mejor("Get Backers 19 - Oh, Mi Amigo... Kazuki vs. Juubei.mp4", hay),
        )
    }

    @Test fun sobrevive_a_los_acentos_y_las_mayusculas() {
        val hay = listOf("get backers capitulo 01.mp4")
        assertEquals(
            "get backers capitulo 01.mp4",
            CoincidenciaDeArchivo.mejor("Get Backers Capítulo 01.mp4", hay),
        )
    }

    @Test fun lo_encuentra_aunque_lo_hayan_movido_de_carpeta() {
        val hay = listOf("videos/Cap 07.mp4")
        assertEquals("videos/Cap 07.mp4", CoincidenciaDeArchivo.mejor("viejo/Cap 07.mkv", hay))
    }

    // ─── por número de capítulo, solo cuando es explícito ──────────────────

    @Test fun usa_el_numero_de_capitulo_cuando_los_dos_lo_dicen() {
        val hay = listOf("Serie.S02E05.1080p.mkv", "Serie.S02E06.1080p.mkv")
        assertEquals(
            "Serie.S02E05.1080p.mkv",
            CoincidenciaDeArchivo.mejor("Vieja Serie S02E05 web-dl.mp4", hay),
        )
    }

    // ─── y acá es donde tiene que rendirse ─────────────────────────────────
    // Un match equivocado reproduce OTRO capítulo sin avisar. Eso es peor que un error honesto.

    @Test fun no_confunde_el_19_con_el_09() {
        val hay = listOf("Get Backers 09.mp4", "Get Backers 20.mp4")
        assertNull(CoincidenciaDeArchivo.mejor("Get Backers 19.mp4", hay))
    }

    @Test fun no_confunde_el_1_con_el_19() {
        val hay = listOf("Get Backers 1.mp4")
        assertNull(CoincidenciaDeArchivo.mejor("Get Backers 19.mp4", hay))
    }

    @Test fun no_elige_cuando_hay_dos_igual_de_buenos() {
        // Dos candidatos con el mismo número: no hay forma de saber cuál. Mejor no jugar.
        val hay = listOf("copia-a/Serie S01E01.mkv", "copia-b/Serie S01E01.mkv")
        assertNull(CoincidenciaDeArchivo.mejor("otra/Serie S01E01.mp4", hay))
    }

    @Test fun sin_candidatos_no_hay_nada_que_encontrar() {
        assertNull(CoincidenciaDeArchivo.mejor("Get Backers 19.mp4", emptyList()))
    }

    @Test fun no_se_agarra_de_un_parecido_vago() {
        val hay = listOf("Otra Serie Completamente Distinta.mp4")
        assertNull(CoincidenciaDeArchivo.mejor("Get Backers 19.mp4", hay))
    }

    @Test fun no_matchea_por_numero_si_solo_uno_lo_declara() {
        // El viejo dice S01E02 y el candidato no dice nada: no hay evidencia, no hay match.
        val hay = listOf("archivo sin numeracion clara.mp4")
        assertNull(CoincidenciaDeArchivo.mejor("Serie S01E02.mkv", hay))
    }
}
