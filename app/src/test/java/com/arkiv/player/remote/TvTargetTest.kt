package com.arkiv.player.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvTargetTest {
    @Test fun `sin parear, otra instalacion de Arkiv en la LAN no cuenta como TV`() {
        // El caso que rompía: Arkiv en el Fire Stick (o en otro celu) de la misma WiFi respondía
        // el broadcast y activaba el diálogo + el icono del control remoto sin pareo.
        assertFalse(tvTargetAvailable(linked = false, lanFound = true, pairedInAccount = false))
    }

    @Test fun `sin parear no hay TV aunque la cuenta reporte una`() {
        assertFalse(tvTargetAvailable(linked = false, lanFound = false, pairedInAccount = true))
    }

    @Test fun `pareado y en la misma WiFi hay TV`() {
        assertTrue(tvTargetAvailable(linked = true, lanFound = true, pairedInAccount = false))
    }

    @Test fun `pareado y fuera de la WiFi hay TV por la nube`() {
        assertTrue(tvTargetAvailable(linked = true, lanFound = false, pairedInAccount = true))
    }

    @Test fun `pareado pero sin LAN ni cuenta resuelta no hay a donde enviar`() {
        assertFalse(tvTargetAvailable(linked = true, lanFound = false, pairedInAccount = false))
    }
}
