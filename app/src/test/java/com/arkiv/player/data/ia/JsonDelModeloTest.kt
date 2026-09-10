package com.arkiv.player.data.ia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonDelModeloTest {

    @Test fun `un arreglo pelado`() {
        assertEquals(2, JsonDelModelo.arreglo("""["a","b"]""").length())
    }

    @Test fun `un arreglo envuelto en cerco de codigo`() {
        val texto = "```json\n[\"uno\", \"dos\"]\n```"
        assertEquals("dos", JsonDelModelo.arreglo(texto).getString(1))
    }

    @Test fun `un arreglo con texto alrededor`() {
        val texto = "Claro, aquí van:\n[1, 2, 3]\nEspero que sirvan."
        assertEquals(3, JsonDelModelo.arreglo(texto).length())
    }

    @Test fun `sin arreglo es ilegible`() {
        assertThrows(JsonIlegible::class.java) { JsonDelModelo.arreglo("no sé") }
    }

    @Test fun `un arreglo roto es ilegible`() {
        assertThrows(JsonIlegible::class.java) { JsonDelModelo.arreglo("[1, 2,") }
    }

    @Test fun `un objeto envuelto`() {
        val texto = "```\n{\"titulo\": \"Coco\"}\n```"
        assertEquals("Coco", JsonDelModelo.objeto(texto).getString("titulo"))
    }

    /** Quien pide un objeto no puede recibir una lista y reventar después en otra parte. */
    @Test fun `un arreglo donde se pidio un objeto es ilegible`() {
        assertThrows(JsonIlegible::class.java) { JsonDelModelo.objeto("""[{"titulo": "Coco"}]""") }
    }
}
