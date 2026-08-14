package com.arkiv.player.pocketbase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * De quién es la base local, y cuándo hay que borrarla porque entró otra persona.
 *
 * MEDIDO EL 2026-08-14: en el Google TV había una cuenta, se desinstaló la app, se reinstaló y se
 * creó una cuenta NUEVA (`juegospc0@gmail.com`). Esa cuenta recién nacida abrió con la biblioteca y
 * los capítulos vistos de la anterior. Y no se quedó en la pantalla: se SUBIÓ a la nube con su
 * accountId. Contra las otras cuentas del sistema:
 *
 * ```
 * library_items:  cristiangdev0=14  cgarcialord=13  laura=2   juegospc0=145
 * episodes:       cristiangdev0=604 cgarcialord=555 laura=11  juegospc0=906
 * ```
 *
 * La cuenta más vieja y más usada tenía 14 ítems; la de ese día, 145. Era la biblioteca del aparato.
 *
 * La causa: `logout()` limpiaba lo local, pero `registrar()` y `login()` no, y las tablas locales no
 * tienen `accountId` — o sea que tampoco se puede filtrar al leer. Cualquier base que quede en el
 * aparato pasa a ser de quien entre después. (La desinstalación tampoco salva: en ese aparato
 * `backup_enabled=0`, así que no fue una restauración de Android — los datos sobrevivieron igual.)
 */
class DuenoDeLaBaseTest {

    private val cuentaA = "eb5c90f7-6214-4eb8-9e23-46665de04d0c"
    private val cuentaB = "b0fd76e6-548d-4843-80cb-c402fe040684"

    @Test fun `entra otra cuenta distinta de la dueña, se borra`() {
        assertTrue(DuenoDeLaBase.hayQueBorrar(duenoGuardado = cuentaB, cuentaQueEntra = cuentaA))
    }

    @Test fun `entra la misma cuenta que ya era dueña, no se toca nada`() {
        assertFalse(DuenoDeLaBase.hayQueBorrar(duenoGuardado = cuentaA, cuentaQueEntra = cuentaA))
    }

    /**
     * EL CASO QUE LO MOTIVA. Sin dueño anotado, los datos que haya son HUÉRFANOS: no hay forma de
     * saber de quién son, y la única respuesta segura es no dárselos a quien entra. En una
     * instalación limpia esto no cuesta nada —no hay nada que borrar— y en una con restos ajenos es
     * exactamente lo que hay que hacer.
     */
    @Test fun `sin dueño anotado se borra, porque los datos son huerfanos`() {
        assertTrue(DuenoDeLaBase.hayQueBorrar(duenoGuardado = null, cuentaQueEntra = cuentaA))
        assertTrue(DuenoDeLaBase.hayQueBorrar(duenoGuardado = "", cuentaQueEntra = cuentaA))
    }

    /**
     * Y el guardia que evita el daño colateral: si la cuenta que entra viene vacía no se borra NADA.
     * Un accountId en blanco es un dato que falta, no "otra persona", y confundirlos le costaría la
     * biblioteca a quien no hizo nada.
     */
    @Test fun `una cuenta en blanco nunca dispara el borrado`() {
        assertFalse(DuenoDeLaBase.hayQueBorrar(duenoGuardado = cuentaB, cuentaQueEntra = ""))
        assertFalse(DuenoDeLaBase.hayQueBorrar(duenoGuardado = null, cuentaQueEntra = ""))
    }

    // ---- La adopción, que es lo que evita borrarle la biblioteca a quien ya la tenía ----

    /**
     * Al actualizar la app, quien ya tenía sesión tiene datos que SÍ son suyos y todavía no hay
     * dueño anotado. Si esa situación se tratara como huérfana, la primera vez que volviera a
     * entrar perdería todo. Por eso se ADOPTA al arrancar: con sesión viva y sin dueño, el dueño
     * pasa a ser esa cuenta, sin borrar nada.
     */
    @Test fun `al actualizar, con sesion viva y sin dueño, se adopta`() {
        assertTrue(DuenoDeLaBase.hayQueAdoptar(duenoGuardado = null, cuentaDeLaSesion = cuentaA))
        assertTrue(DuenoDeLaBase.hayQueAdoptar(duenoGuardado = "", cuentaDeLaSesion = cuentaA))
    }

    @Test fun `no se adopta si ya hay dueño`() {
        assertFalse(DuenoDeLaBase.hayQueAdoptar(duenoGuardado = cuentaB, cuentaDeLaSesion = cuentaA))
    }

    /** Sin sesión no hay a quién adoptar: los datos siguen huérfanos hasta que alguien entre. */
    @Test fun `no se adopta sin sesion`() {
        assertFalse(DuenoDeLaBase.hayQueAdoptar(duenoGuardado = null, cuentaDeLaSesion = ""))
        assertFalse(DuenoDeLaBase.hayQueAdoptar(duenoGuardado = null, cuentaDeLaSesion = null))
    }
}
