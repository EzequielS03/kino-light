package com.arkiv.player.sync

/**
 * La regla con la que se mergea un snapshot remoto en la base local: **last-write-wins por
 * `updatedAt`**, la misma que ya usa el sync por nube (`cloudsync.LwwMerge`).
 *
 * Antes el LAN funcionaba como **espejo one-way**: el TV adoptaba la biblioteca del celu y borraba
 * en duro todo ítem que el celu no tuviera. Eso hacía que lo agregado EN EL TV desapareciera solo
 * en el siguiente sync. El espejo existía porque, sin un reloj por fila, "el otro no la tiene" es
 * ambiguo: no se puede distinguir *"la agregué acá"* de *"la borré allá"*. Ya no hace falta
 * adivinar: `items`/`episodes` tienen `updatedAt` (que los triggers sellan en cada escritura local)
 * y `deleted` (tombstone), así que un borrado viaja como una fila más y **la ausencia ya no
 * significa nada**.
 *
 * Puro/JVM para poder probar la regla sin Room, que es donde de verdad se puede equivocar uno.
 */
object SyncMerge {

    /**
     * Las filas remotas que hay que escribir localmente: las que no existen acá, y las que existen
     * pero con un `updatedAt` menor. Las locales que el remoto no trae **no se tocan**.
     *
     * El empate NO se escribe a propósito: reescribir una fila idéntica haría que el trigger le
     * suba el `updatedAt`, y las dos puntas se quedarían pisándose en cada sync para siempre.
     */
    fun <T> aAplicar(
        locales: List<T>,
        remotas: List<T>,
        llave: (T) -> String,
        updatedAt: (T) -> Long,
    ): List<T> {
        val relojLocal = locales.associate { llave(it) to updatedAt(it) }
        return remotas.filter { remota ->
            val local = relojLocal[llave(remota)]
            local == null || updatedAt(remota) > local
        }
    }
}
