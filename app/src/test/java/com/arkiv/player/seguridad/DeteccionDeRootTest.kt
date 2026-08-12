package com.arkiv.player.seguridad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Qué se considera un aparato rooteado.
 *
 * El caso que más importa que NO falle es el falso positivo: bloquear un aparato de fábrica deja al
 * dueño sin app y sin forma de arreglarlo. Por eso el primer test es el Fire TV real, con sus
 * valores medidos por ADB el 2026-08-12.
 */
class DeteccionDeRootTest {

    @Test fun `un fire tv de fabrica no se considera rooteado`() {
        // Medido en el AFTKM: `tags` trae mas que "release-keys", asi que comparar la cadena entera
        // contra "release-keys" lo habria bloqueado.
        val senales = SenalesDeRoot(tags = "amz-p,release-keys", tipo = "user")
        assertEquals(emptyList<String>(), DeteccionDeRoot.motivos(senales))
        assertFalse(DeteccionDeRoot.hayRoot(senales))
    }

    @Test fun `un telefono de fabrica tampoco`() {
        val senales = SenalesDeRoot(tags = "release-keys", tipo = "user")
        assertFalse(DeteccionDeRoot.hayRoot(senales))
    }

    @Test fun `el binario su delata`() {
        val senales = SenalesDeRoot(binariosSu = listOf("/system/xbin/su"), tags = "release-keys", tipo = "user")
        assertTrue(DeteccionDeRoot.hayRoot(senales))
        assertTrue(DeteccionDeRoot.motivos(senales).single().contains("/system/xbin/su"))
    }

    @Test fun `la app de magisk delata`() {
        val senales = SenalesDeRoot(paquetesDeRoot = listOf("com.topjohnwu.magisk"), tipo = "user")
        assertTrue(DeteccionDeRoot.hayRoot(senales))
    }

    @Test fun `los rastros de magisk delatan aunque su app no este`() {
        // El caso de quien desinstala la app pero deja el root puesto.
        val senales = SenalesDeRoot(rastrosDeMagisk = listOf("/data/adb/magisk"), tipo = "user")
        assertTrue(DeteccionDeRoot.hayRoot(senales))
    }

    @Test fun `una rom firmada con test-keys delata`() {
        assertTrue(DeteccionDeRoot.hayRoot(SenalesDeRoot(tags = "test-keys", tipo = "user")))
    }

    @Test fun `una compilacion userdebug delata`() {
        assertTrue(DeteccionDeRoot.hayRoot(SenalesDeRoot(tags = "release-keys", tipo = "userdebug")))
    }

    // --- Montajes: lo unico que sobrevive a Shamiko -------------------------------------------

    @Test fun `un montaje que apunta a data adb es sospechoso`() {
        assertTrue(DeteccionDeRoot.montajeEsSospechoso("123 45 0:1 / /system/bin rw - ext4 /data/adb/modules rw"))
    }

    @Test fun `un overlay sobre system es sospechoso`() {
        assertTrue(DeteccionDeRoot.montajeEsSospechoso("36 24 0:29 / /system rw,relatime - overlay overlay rw"))
    }

    @Test fun `un tmpfs normal fuera de system no lo es`() {
        assertFalse(DeteccionDeRoot.montajeEsSospechoso("22 20 0:18 / /dev rw,nosuid - tmpfs tmpfs rw"))
        assertFalse(DeteccionDeRoot.montajeEsSospechoso("30 24 253:5 / /data rw,nosuid - ext4 /dev/block/dm-5 rw"))
    }

    @Test fun `dos hilos con tablas distintas delatan la manipulacion del namespace`() {
        // El namespace de montaje es del PROCESO: esto no puede pasar en un aparato limpio, y es lo
        // que queda cuando se esconde todo lo demas.
        val senales = SenalesDeRoot(tags = "release-keys", tipo = "user", montajesInconsistentes = true)
        assertTrue(DeteccionDeRoot.hayRoot(senales))
    }

    @Test fun `se acumulan todos los motivos, no solo el primero`() {
        // La pantalla de bloqueo los muestra: un aviso que no explica nada es indistinguible de un bug.
        val senales = SenalesDeRoot(
            binariosSu = listOf("/sbin/su"),
            paquetesDeRoot = listOf("com.topjohnwu.magisk"),
            tags = "test-keys",
            tipo = "userdebug",
        )
        assertEquals(4, DeteccionDeRoot.motivos(senales).size)
    }
}
