package com.arkiv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [mensajeErrorVivo] es lo que decide si, al fallar la apertura de un canal, la pantalla muestra
 * algo que la persona puede arreglar o un "no se pudo" que no dice nada.
 */
class MensajeErrorVivoTest {

    @Test
    fun `sin cuenta de Magis el mensaje dice que hay que vincularla`() {
        val msg = mensajeErrorVivo(hayCuentaDeMagis = false, nombreCanal = "Canal 5")

        assertTrue("debe nombrar la cuenta de Magis: $msg", msg.contains("cuenta de Magis"))
        assertTrue("debe decir dónde vincularla: $msg", msg.contains("Ajustes"))
        // No nombra el canal: el problema no es ESE canal, es que el vivo entero no anda.
        assertTrue(!msg.contains("Canal 5"))
    }

    @Test
    fun `con cuenta vinculada el fallo es del canal y se lo nombra`() {
        assertEquals(
            "No se pudo abrir Canal 5",
            mensajeErrorVivo(hayCuentaDeMagis = true, nombreCanal = "Canal 5"),
        )
    }
}
