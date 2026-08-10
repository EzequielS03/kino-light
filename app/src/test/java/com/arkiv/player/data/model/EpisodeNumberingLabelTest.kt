package com.arkiv.player.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Casos tomados de la base REAL del Fire Stick (431 episodios): los displayName vienen en formatos
 * muy distintos y algunos traen la sinopsis entera pegada. Por eso el rótulo se arma parseando,
 * no mostrando el texto crudo.
 */
class EpisodeNumberingLabelTest {

    @Test fun `formato sXXeYY (Dragon Ball GT)`() {
        assertEquals("T1 · E1", EpisodeNumbering.displayLabel(null, "s01e01"))
        assertEquals("T1 · E16", EpisodeNumbering.displayLabel(null, "s01e16"))
    }

    @Test fun `formato T y E limpio`() {
        assertEquals("T1 · E3", EpisodeNumbering.displayLabel(null, "T1 · E3"))
        assertEquals("T1 · E100", EpisodeNumbering.displayLabel(null, "T1 · E100  El brujo de las ranas"))
    }

    @Test fun `con la sinopsis pegada se queda solo con el numero`() {
        // El "E181" que sigue es la numeración absoluta; gana el primer match (E23).
        val sucio = "T4 · E23  E181 La verdad de por qué el Hoshikage fue silenciado Tras salvarle " +
            "la vida, Natsuhi le explica a Naruto cómo ella y su marido pusieron fin a los horrores " +
            "del entrenamiento de la estrella. 19/04/2006"
        assertEquals("T4 · E23", EpisodeNumbering.displayLabel(null, sucio))
    }

    @Test fun `sin temporada muestra solo el capitulo`() {
        assertEquals("E7", EpisodeNumbering.displayLabel(null, "Ep 7"))
        assertEquals("E5", EpisodeNumbering.displayLabel(null, "Episodio 5"))
    }

    @Test fun `la temporada puede venir de la seccion`() {
        assertEquals("T2 · E7", EpisodeNumbering.displayLabel("Temporada 2", "Ep 7"))
    }

    @Test fun `texto sin marca de capitulo no inventa rotulo`() {
        assertNull(EpisodeNumbering.displayLabel(null, "TPO Neon Genesis Evangelion 04  · Trapo2019 Universo Anime"))
        assertNull(EpisodeNumbering.displayLabel(null, ""))
    }

    @Test fun `no confunde un titulo que empieza por T o E con la numeracion`() {
        assertNull(EpisodeNumbering.displayLabel(null, "Terminator"))
        assertNull(EpisodeNumbering.displayLabel(null, "Everest"))
    }
}
