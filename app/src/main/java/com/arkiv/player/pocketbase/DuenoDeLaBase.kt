package com.arkiv.player.pocketbase

/**
 * De quién es la base local, y cuándo hay que borrarla porque entró otra persona.
 *
 * Existe por una fuga entre cuentas del 2026-08-14. En el Google TV había una cuenta, se desinstaló
 * la app, se reinstaló y se creó una cuenta NUEVA. Esa cuenta recién nacida abrió con la biblioteca
 * y los capítulos vistos de la anterior — y no se quedó en la pantalla: se SUBIÓ a la nube con su
 * accountId. Contra las otras cuentas del sistema:
 *
 * ```
 * library_items:  cristiangdev0=14  cgarcialord=13  laura=2   juegospc0=145
 * episodes:       cristiangdev0=604 cgarcialord=555 laura=11  juegospc0=906
 * ```
 *
 * La cuenta más vieja y más usada tenía 14 ítems; la nueva, 145. Era la biblioteca del aparato.
 *
 * La causa: `logout()` limpiaba lo local, pero `registrar()` y `login()` no. Y las tablas locales no
 * tienen `accountId`, así que tampoco se puede filtrar al leer: la base es de quien la tenga. El
 * diseño dependía por completo de que se borrara al SALIR, y nadie sale — se desinstala, se cambia
 * de cuenta, se parea otro aparato. (La desinstalación tampoco salva: en ese aparato
 * `backup_enabled=0`, así que no fue una restauración de Android y los datos sobrevivieron igual.)
 *
 * La regla es pura para poder fijar los bordes por test, que es donde esto se vuelve peligroso en la
 * otra dirección: borrar de más le cuesta la biblioteca entera a alguien que no hizo nada.
 */
object DuenoDeLaBase {

    /**
     * Si al entrar [cuentaQueEntra] hay que borrar la base local.
     *
     * Sin dueño anotado los datos son HUÉRFANOS: no hay forma de saber de quién son, y la única
     * respuesta segura es no dárselos a quien entra. En una instalación limpia eso no cuesta nada
     * —no hay nada que borrar— y en una con restos ajenos es exactamente lo que hace falta.
     *
     * Una [cuentaQueEntra] en blanco NUNCA borra: un accountId vacío es un dato que falta, no "otra
     * persona", y confundir las dos cosas le costaría la biblioteca a quien no hizo nada.
     */
    fun hayQueBorrar(duenoGuardado: String?, cuentaQueEntra: String): Boolean {
        if (cuentaQueEntra.isBlank()) return false
        return duenoGuardado.isNullOrBlank() || duenoGuardado != cuentaQueEntra
    }

    /**
     * Si hay que ADOPTAR [cuentaDeLaSesion] como dueña de lo que ya está en el aparato.
     *
     * Es la migración, y evita el daño colateral: al actualizar la app, quien ya tenía sesión tiene
     * datos que SÍ son suyos y todavía no hay dueño anotado. Si esa situación se tratara como
     * huérfana, la primera vez que volviera a entrar perdería todo. Con sesión viva y sin dueño, el
     * dueño pasa a ser esa cuenta y no se borra nada.
     *
     * Sin sesión no hay a quién adoptar: los datos siguen huérfanos hasta que alguien entre, y ahí
     * decide [hayQueBorrar].
     */
    fun hayQueAdoptar(duenoGuardado: String?, cuentaDeLaSesion: String?): Boolean =
        duenoGuardado.isNullOrBlank() && !cuentaDeLaSesion.isNullOrBlank()
}
