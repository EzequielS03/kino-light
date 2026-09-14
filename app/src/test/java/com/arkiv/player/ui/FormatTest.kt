package com.arkiv.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainSynopsisTest {
    @Test fun `quita tags html de anilist`() {
        assertEquals(
            "Un profesor de química. Nota: spoiler",
            plainSynopsis("Un profesor de química.<br><br><i>Nota:</i> spoiler"),
        )
    }

    @Test fun `decodifica entidades basicas`() {
        assertEquals(
            "Tom & Jerry \"el mejor\" y algo más",
            plainSynopsis("Tom &amp; Jerry &quot;el mejor&quot;&nbsp;y algo más"),
        )
    }

    @Test fun `no decodifica dos veces una entidad escapada`() {
        // "&amp;lt;" is a literal "&lt;" written on purpose: it must stay "&lt;", not become "<".
        assertEquals("&lt;b&gt;", plainSynopsis("&amp;lt;b&amp;gt;"))
    }

    @Test fun `colapsa saltos de linea y espacios multiples`() {
        assertEquals("Una línea y otra", plainSynopsis("Una línea\n\n   y    otra"))
    }

    @Test fun `solo markup queda vacio`() {
        assertEquals("", plainSynopsis("<p></p><br>"))
    }

    @Test fun `null y blanco quedan vacios`() {
        assertEquals("", plainSynopsis(null))
        assertEquals("", plainSynopsis("   "))
    }

    @Test fun `texto ya limpio es idempotente`() {
        val limpio = "Un profesor de química con cáncer terminal."
        assertEquals(limpio, plainSynopsis(limpio))
        assertEquals(limpio, plainSynopsis(plainSynopsis(limpio)))
    }
}

class HeroFallbackTest {
    @Test fun `serie con nombre de capitulo pierde el titulo`() {
        assertEquals(
            "S01E03 · Glorious Purpose",
            heroFallback("Loki", "Loki · S01E03 · Glorious Purpose"),
        )
    }

    @Test fun `serie sin nombre de capitulo`() {
        assertEquals("S01E03", heroFallback("Loki", "Loki · S01E03"))
    }

    @Test fun `pelicula con el titulo repetido queda vacia`() {
        assertEquals("", heroFallback("Dune", "Dune"))
    }

    @Test fun `la comparacion ignora mayusculas`() {
        assertEquals("", heroFallback("Dune", "dune"))
        assertEquals("S01E03", heroFallback("Loki", "loki · S01E03"))
    }

    @Test fun `si el titulo cambio despues el displayName queda intacto`() {
        // The label was formatted with a different showTitle: there's no prefix to strip.
        assertEquals("Loki · S01E03", heroFallback("Loki 2021", "Loki · S01E03"))
    }

    @Test fun `tolera espacios de borde en el titulo`() {
        assertEquals("S01E03", heroFallback("  Loki  ", "Loki · S01E03"))
    }
}

class HeroSubtitleTest {
    @Test fun `una sinopsis de verdad se usa tal cual`() {
        assertEquals(
            "Un profesor de química con cáncer terminal.",
            heroSubtitle("Breaking Bad", "Un profesor de química con cáncer terminal.", "5 episodios"),
        )
    }

    @Test fun `la descripcion que es el titulo repetido cae al respaldo`() {
        // Real archive.org case: whoever uploads the file puts the title as the description.
        assertEquals(
            "1 h 36 min",
            heroSubtitle("Night Of The Living Dead 1990", "Night of the living dead 1990", "1 h 36 min"),
        )
    }

    @Test fun `sin descripcion cae al respaldo`() {
        assertEquals("12 episodios", heroSubtitle("Loki", null, "12 episodios"))
        assertEquals("12 episodios", heroSubtitle("Loki", "   ", "12 episodios"))
    }

    @Test fun `descripcion que solo era markup cae al respaldo`() {
        assertEquals("12 episodios", heroSubtitle("Loki", "<p></p>", "12 episodios"))
    }

    @Test fun `una sinopsis que empieza con el titulo no se descarta`() {
        // Starts with the name but keeps going: it's a legitimate synopsis, not a duplication.
        val sinopsis = "Avatar Aang, el último Maestro Aire del mundo, se entera de un antiguo poder."
        assertEquals(sinopsis, heroSubtitle("Avatar: Aang", sinopsis, "20 episodios"))
    }

    @Test fun `limpia el html antes de comparar con el titulo`() {
        assertEquals("1 h 36 min", heroSubtitle("Dune", "<p>Dune</p>", "1 h 36 min"))
    }
}
