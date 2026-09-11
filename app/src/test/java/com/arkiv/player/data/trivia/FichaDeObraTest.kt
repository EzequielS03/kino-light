package com.arkiv.player.data.trivia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON de ejemplo escrito a mano con los campos reales de TMDB (`append_to_response=credits` para
 * película y capítulo, `aggregate_credits` para serie). El `overview` de cada uno lleva una palabra
 * única que no debe aparecer nunca en [FichaDeObra.renglones]: el dato curioso no puede tener
 * spoilers.
 */
class FichaDeObraTest {

    private val peliculaJson = """
        {
          "title": "Coco",
          "release_date": "2017-10-27",
          "runtime": 105,
          "overview": "PALABRAUNICATRAMAPELICULA",
          "production_companies": [{"name": "Pixar Animation Studios"}],
          "credits": {
            "cast": [
              {"name": "Anthony Gonzalez", "order": 0},
              {"name": "Gael García Bernal", "order": 1},
              {"name": "Benjamin Bratt", "order": 2},
              {"name": "Alanna Ubach", "order": 3},
              {"name": "Renée Victor", "order": 4},
              {"name": "Jaime Camil", "order": 5}
            ],
            "crew": [
              {"name": "Lee Unkrich", "job": "Director", "department": "Directing"},
              {"name": "Adrian Molina", "job": "Co-Director", "department": "Directing"},
              {"name": "Adrian Molina", "job": "Screenplay", "department": "Writing"},
              {"name": "Matthew Aldrich", "job": "Screenplay", "department": "Writing"}
            ]
          }
        }
    """.trimIndent()

    private val serieJson = """
        {
          "name": "Naruto",
          "first_air_date": "2002-10-03",
          "overview": "PALABRAUNICATRAMASERIE",
          "created_by": [{"name": "Masashi Kishimoto"}],
          "networks": [{"name": "TV Tokyo"}],
          "aggregate_credits": {
            "cast": [
              {"name": "Junko Takeuchi", "order": 0},
              {"name": "Chie Nakamura", "order": 1},
              {"name": "Noriaki Sugiyama", "order": 2},
              {"name": "Kazuhiko Inoue", "order": 3},
              {"name": "Hidekatsu Shibata", "order": 4},
              {"name": "Alguien Más", "order": 5}
            ]
          }
        }
    """.trimIndent()

    private val capituloJson = """
        {
          "name": "¡Soy Konohamaru!",
          "air_date": "2002-10-10",
          "season_number": 1,
          "episode_number": 2,
          "overview": "PALABRAUNICATRAMACAPITULO",
          "crew": [
            {"name": "Hayato Date", "job": "Director", "department": "Directing"},
            {"name": "Junki Takegami", "job": "Writer", "department": "Writing"}
          ],
          "guest_stars": [
            {"name": "Invitado Uno"},
            {"name": "Invitado Dos"},
            {"name": "Invitado Tres"},
            {"name": "Invitado Cuatro"},
            {"name": "Invitado Cinco"},
            {"name": "Invitado Seis"}
          ]
        }
    """.trimIndent()

    @Test fun `la ficha de una pelicula saca director, guionistas, reparto, productoras, fecha y duracion`() {
        val f = fichaDePelicula(peliculaJson)!!
        assertEquals("movie", f.tipo)
        assertEquals("Coco", f.nombre)
        assertEquals("2017-10-27", f.fechaEstreno)
        assertEquals(105, f.duracionMinutos)
        // Solo "Director" exacto: "Co-Director" no cuenta como director.
        assertEquals(listOf("Lee Unkrich"), f.directores)
        assertEquals(listOf("Adrian Molina", "Matthew Aldrich"), f.guionistas)
        assertEquals(listOf("Pixar Animation Studios"), f.productoras)
        // Los primeros 5 por `order`: Jaime Camil (order 5) queda afuera.
        assertEquals(
            listOf("Anthony Gonzalez", "Gael García Bernal", "Benjamin Bratt", "Alanna Ubach", "Renée Victor"),
            f.reparto,
        )
    }

    @Test fun `la ficha de una serie saca creadores, cadena, primera emision y reparto`() {
        val f = fichaDeSerie(serieJson)!!
        assertEquals("tv", f.tipo)
        assertEquals("Naruto", f.nombre)
        assertEquals("2002-10-03", f.fechaEstreno)
        assertEquals(listOf("Masashi Kishimoto"), f.creadores)
        assertEquals(listOf("TV Tokyo"), f.cadenas)
        assertEquals(
            listOf("Junko Takeuchi", "Chie Nakamura", "Noriaki Sugiyama", "Kazuhiko Inoue", "Hidekatsu Shibata"),
            f.reparto,
        )
    }

    @Test fun `el capitulo saca nombre, fecha, director, guionista e invitados`() {
        val c = capituloDeFicha(capituloJson)!!
        assertEquals(1, c.temporada)
        assertEquals(2, c.episodio)
        assertEquals("¡Soy Konohamaru!", c.nombre)
        assertEquals("2002-10-10", c.fecha)
        assertEquals(listOf("Hayato Date"), c.directores)
        assertEquals(listOf("Junki Takegami"), c.guionistas)
        // Los primeros 5: "Invitado Seis" queda afuera.
        assertEquals(listOf("Invitado Uno", "Invitado Dos", "Invitado Tres", "Invitado Cuatro", "Invitado Cinco"), c.invitados)
    }

    @Test fun `un null o un campo ausente no aparece en renglones`() {
        val json = """{"title": "Sin datos", "release_date": null}"""
        val f = fichaDePelicula(json)!!
        assertNull(f.fechaEstreno)
        assertTrue(f.directores.isEmpty())
        assertTrue(f.productoras.isEmpty())
        // Sin ningún hecho más allá del nombre, no hay bloque que mostrar.
        assertEquals("", f.renglones())
    }

    @Test fun `ningun campo overview llega a renglones`() {
        val pelicula = fichaDePelicula(peliculaJson)!!.renglones()
        val serie = fichaDeSerie(serieJson)!!.renglones()
        val serieConCapitulo = fichaDeSerie(serieJson)!!.copy(capitulo = capituloDeFicha(capituloJson))
        assertFalse(pelicula.contains("PALABRAUNICATRAMAPELICULA"))
        assertFalse(serie.contains("PALABRAUNICATRAMASERIE"))
        assertFalse(serieConCapitulo.renglones().contains("PALABRAUNICATRAMACAPITULO"))
    }

    @Test fun `un json roto da null`() {
        assertNull(fichaDePelicula("{esto no es json"))
        assertNull(fichaDeSerie("{esto no es json"))
        assertNull(capituloDeFicha("{esto no es json"))
    }

    @Test fun `renglones arma serie y capitulo como en el ejemplo del brief`() {
        val ficha = fichaDeSerie(serieJson)!!.copy(capitulo = capituloDeFicha(capituloJson))
        val r = ficha.renglones()
        assertTrue(r.contains("Serie: Naruto (primera emisión 2002-10-03; creada por Masashi Kishimoto; canal TV Tokyo; reparto:"))
        assertTrue(
            r.contains(
                "Capítulo: temporada 1, episodio 2, «¡Soy Konohamaru!» (emitido 2002-10-10; " +
                    "dirigido por Hayato Date; escrito por Junki Takegami; invitados: Invitado Uno, " +
                    "Invitado Dos, Invitado Tres, Invitado Cuatro, Invitado Cinco)",
            ),
        )
    }
}
