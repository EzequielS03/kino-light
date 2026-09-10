package com.arkiv.player.data.ia

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogoDeKiloTest {

    private fun modelo(
        id: String,
        precio: Any? = "0",
        tools: Boolean = true,
        nombre: String = id,
        descripcion: String = "a general chat model",
    ): String {
        val params = if (tools) """["tools","temperature"]""" else """["temperature"]"""
        val pricing = if (precio == null) "{}" else """{"prompt": ${JSONObject.quote(precio.toString())}}"""
        return """{"id":"$id","name":${JSONObject.quote(nombre)},"description":${JSONObject.quote(descripcion)},
                   "pricing":$pricing,"supported_parameters":$params}"""
    }

    private fun catalogo(vararg modelos: String) = JSONObject("""{"data":[${modelos.joinToString(",")}]}""")

    @Test fun `gratis y con tools entra`() {
        val c = CatalogoDeKilo.candidatos(catalogo(modelo("nvidia/nemotron-3-super:free")))
        assertEquals(listOf("nvidia/nemotron-3-super:free"), c.map { it.id })
    }

    @Test fun `de pago no entra`() {
        assertTrue(CatalogoDeKilo.candidatos(catalogo(modelo("pago/modelo", precio = "0.0000015"))).isEmpty())
    }

    @Test fun `gratis sin tools no entra`() {
        assertTrue(CatalogoDeKilo.candidatos(catalogo(modelo("sin/tools:free", tools = false))).isEmpty())
    }

    @Test fun `sin precio no es gratis`() {
        assertTrue(CatalogoDeKilo.candidatos(catalogo(modelo("sin/precio", precio = null))).isEmpty())
    }

    @Test fun `un precio con decimales en cero es gratis`() {
        assertTrue(CatalogoDeKilo.esGratis(JSONObject(modelo("x", precio = "0.0000"))))
    }

    /** El clasificador que en llm-libre llegó a ser el primero del ranking respondiendo "safe" a todo. */
    @Test fun `un clasificador de seguridad no entra`() {
        val m = modelo(
            "nvidia/nemotron-3.5-content-safety:free",
            descripcion = "A content safety classifier that flags unsafe prompts",
        )
        assertTrue(CatalogoDeKilo.candidatos(catalogo(m)).isEmpty())
    }

    @Test fun `un meta router al azar no entra`() {
        val m = modelo("kilo-auto/free", descripcion = "Rotates through available free models")
        assertTrue(CatalogoDeKilo.candidatos(catalogo(m)).isEmpty())
    }

    @Test fun `un modelo de embeddings no entra`() {
        val m = modelo("x/emb:free", nombre = "Text Embeddings Small")
        assertTrue(CatalogoDeKilo.candidatos(catalogo(m)).isEmpty())
    }

    @Test fun `conserva el orden del catalogo`() {
        val c = CatalogoDeKilo.candidatos(catalogo(modelo("b:free"), modelo("a:free")))
        assertEquals(listOf("b:free", "a:free"), c.map { it.id })
    }

    @Test fun `un catalogo sin data da vacio`() {
        assertTrue(CatalogoDeKilo.candidatos(JSONObject("{}")).isEmpty())
    }

    @Test fun `esGratis con precio numerico`() {
        assertTrue(CatalogoDeKilo.esGratis(JSONObject("""{"pricing":{"prompt":0}}""")))
        assertFalse(CatalogoDeKilo.esGratis(JSONObject("""{"pricing":{"prompt":0.1}}""")))
    }
}
