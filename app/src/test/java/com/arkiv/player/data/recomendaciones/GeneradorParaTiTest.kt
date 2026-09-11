package com.arkiv.player.data.recomendaciones

import com.arkiv.player.data.db.RecomendacionEntity
import com.arkiv.player.data.ia.RespuestaDeIa
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneradorParaTiTest {

    private val HORA = 60 * 60 * 1000L

    @Test fun `la puerta abre la primera vez`() {
        assertTrue(PuertaDeParaTi.toca(ultimoIntentoMs = 0, ultimoFueFalloDelModelo = false, ahoraMs = 1))
    }

    @Test fun `la puerta espera 24 horas`() {
        assertFalse(PuertaDeParaTi.toca(1_000, false, 1_000 + 23 * HORA))
        assertTrue(PuertaDeParaTi.toca(1_000, false, 1_000 + 24 * HORA))
    }

    /** Un modelo caído no gasta la ventana entera: se reintenta a los 15 minutos. */
    @Test fun `tras un fallo del modelo espera 15 minutos`() {
        assertFalse(PuertaDeParaTi.toca(1_000, true, 1_000 + 14 * 60 * 1000L))
        assertTrue(PuertaDeParaTi.toca(1_000, true, 1_000 + 15 * 60 * 1000L))
    }

    @Test fun `la instruccion es la del gateway con el historial debajo`() {
        val i = PreguntaParaTi.instruccion("- Coco (movie): terminado")
        assertTrue(i.startsWith("Eres un recomendador de películas y series para una persona de Colombia."))
        assertTrue(i.contains("Propón 20 títulos que NO estén en la lista."))
        assertTrue(i.endsWith("\n\n- Coco (movie): terminado"))
    }

    @Test fun `los candidatos validos se leen y los rotos se tiran`() {
        val texto = """```json
            [{"titulo":"Coco","anio":"2017","tipo":"movie","porque":"porque viste Encanto"},
             {"titulo":"","tipo":"movie","porque":"x"},
             "no soy un objeto",
             {"titulo":"Naruto","anio":2002,"tipo":"serie","porque":"porque viste Bleach"},
             {"titulo":"Dark","anio":"dos mil","tipo":"tv","porque":"x"}]```"""
        val c = PreguntaParaTi.candidatos(texto)
        assertEquals(listOf("Coco", "Naruto", "Dark"), c.map { it.titulo })
        assertEquals("2002", c[1].anio)
        // Un tipo desconocido cae a película: TMDB confirma el real en la cascada.
        assertEquals("movie", c[1].tipo)
        assertEquals("tv", c[2].tipo)
        // Un año que no es un número no tumba al candidato: queda vacío.
        assertEquals("", c[2].anio)
    }

    // --- el generador ---------------------------------------------------------

    private class Guardado { var ultima: List<RecomendacionEntity>? = null }

    private fun generador(
        ia: (String) -> RespuestaDeIa,
        vistas: List<Vista> = listOf(Vista("Encanto", "movie", "terminado")),
        verificar: (List<Candidato>) -> List<Verificada> = { candidatos ->
            candidatos.mapIndexed { i, c -> Verificada(c, i + 1, c.tipo, c.titulo, "p$i", "magis1:${c.tipo}:0:C$i") }
        },
        guardado: Guardado = Guardado(),
        marcas: MutableMap<String, Any> = mutableMapOf(),
    ) = GeneradorParaTi(
        ia = { ia(it) },
        historial = { vistas },
        yaVistos = { emptySet() },
        verificar = { candidatos, _ -> verificar(candidatos) },
        guardar = { guardado.ultima = it },
        leerMarcas = { (marcas["t"] as? Long ?: 0L) to (marcas["f"] as? Boolean ?: false) },
        escribirMarcas = { t, f -> marcas["t"] = t; marcas["f"] = f },
        ahoraMs = { 10 * HORA },
    )

    private val respuestaBuena = RespuestaDeIa.Texto(
        """[{"titulo":"Coco","anio":"2017","tipo":"movie","porque":"porque viste Encanto"}]""", "m",
    )

    @Test fun `un exito guarda con orden, id de la fuente y porque`() = runTest {
        val g = Guardado()
        generador(ia = { respuestaBuena }, guardado = g).generarSiToca()
        val r = g.ultima!!.single()
        assertEquals(com.arkiv.player.data.MagisEntities.itemIdDe("C0"), r.id)
        assertEquals(0, r.orden)
        assertEquals("porque viste Encanto", r.porque)
        assertEquals("magis1:movie:0:C0", r.ref)
        assertEquals(10 * HORA, r.generadoAt)
    }

    @Test fun `la misma obra dos veces entra una sola`() = runTest {
        val g = Guardado()
        generador(
            ia = { respuestaBuena },
            verificar = { c -> List(2) { Verificada(c.first(), 1, "movie", "Coco", "p", "magis1:movie:0:C7") } },
            guardado = g,
        ).generarSiToca()
        assertEquals(1, g.ultima!!.size)
    }

    @Test fun `si el modelo no contesta no se borra nada y se marca el fallo`() = runTest {
        val g = Guardado()
        val marcas = mutableMapOf<String, Any>()
        generador(ia = { RespuestaDeIa.NoPude }, guardado = g, marcas = marcas).generarSiToca()
        assertNull(g.ultima)
        assertEquals(true, marcas["f"])
    }

    @Test fun `si el modelo contesta algo ilegible es un fallo del modelo`() = runTest {
        val marcas = mutableMapOf<String, Any>()
        generador(ia = { RespuestaDeIa.Texto("no sé", "m") }, marcas = marcas).generarSiToca()
        assertEquals(true, marcas["f"])
    }

    @Test fun `si no queda ninguna verificada no se borra nada`() = runTest {
        val g = Guardado()
        generador(ia = { respuestaBuena }, verificar = { emptyList() }, guardado = g).generarSiToca()
        assertNull(g.ultima)
    }

    /** `generarSiToca` corre en `applicationScope`: una excepción suelta tumbaría la app. */
    @Test fun `una excepcion no se escapa ni borra nada`() = runTest {
        val g = Guardado()
        val marcas = mutableMapOf<String, Any>()
        generador(ia = { respuestaBuena }, verificar = { error("se cayó la red") }, guardado = g, marcas = marcas)
            .generarSiToca()
        assertNull(g.ultima)
        assertEquals(true, marcas["f"])
    }

    @Test fun `sin historial no se le pregunta al modelo`() = runTest {
        var preguntas = 0
        generador(ia = { preguntas++; respuestaBuena }, vistas = emptyList()).generarSiToca()
        assertEquals(0, preguntas)
    }

    @Test fun `con la puerta cerrada no hace nada`() = runTest {
        var preguntas = 0
        val marcas = mutableMapOf<String, Any>("t" to 10 * HORA - 1, "f" to false)
        generador(ia = { preguntas++; respuestaBuena }, marcas = marcas).generarSiToca()
        assertEquals(0, preguntas)
    }

    @Test fun `un exito marca el intento sin fallo`() = runTest {
        val marcas = mutableMapOf<String, Any>()
        generador(ia = { respuestaBuena }, marcas = marcas).generarSiToca()
        assertEquals(10 * HORA, marcas["t"])
        assertEquals(false, marcas["f"])
    }
}
