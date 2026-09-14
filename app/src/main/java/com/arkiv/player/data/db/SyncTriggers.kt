package com.arkiv.player.data.db

/**
 * El DDL que mantiene `updatedAt` al día en estas seis tablas.
 *
 * `updatedAt` was the clock two removed cloud-sync paths depended on: the merge used it to decide
 * who won between two devices' copies of a row (`cloudsync.LwwMerge`, `sync.SyncMerge`), and the
 * push picked what to upload with `updatedAt > cursor` -- a row stuck at 0 never cleared that
 * cursor and never got uploaded. Neither exists anymore in this branch. The `updatedAt`/`deleted`
 * columns stay because dropping a column is a schema change; the triggers themselves aren't part
 * of Room's schema at all (see below), so they could be deleted freely -- nothing has needed to
 * yet, so they're still here sealing every local write with a clock nothing reads.
 *
 * Vivía solo dentro de `MIGRATION_6_7`, y ahí estaba el problema: Room crea las tablas desde su
 * esquema generado, y los triggers no son parte de ese esquema. Un aparato **instalado de cero** en
 * una versión posterior nunca corre esa migración, así que nunca tuvo los triggers y todo lo que
 * creaba localmente nacía —y se quedaba— en `updatedAt = 0`. Medido el 2026-08-10: el Fire TV tenía
 * cero triggers y 77 de 116 ítems, 1054 de 1806 episodios y 7 filas de progreso sin sellar; el
 * celular, que sí venía migrando desde v6, los tenía todos. Por eso lo que se veía o guardaba en la
 * TV no llegaba nunca al celular.
 *
 * Por eso [ddl] se aplica en CADA apertura de la base y no en una migración, y así vale igual
 * para el que migra y para el que instala de cero. Lo que hace seguro correrlo en cada apertura
 * NO es el `IF NOT EXISTS` de los `CREATE TRIGGER` -- es el `DROP TRIGGER IF EXISTS` que precede
 * a cada uno (el porqué, con el bug que causó no tenerlo, está en el KDoc de [ddl]).
 */
object SyncTriggers {

    /**
     * Tabla → su clave primaria. **La de verdad**: es la que va en el `WHERE` del trigger, así que
     * si acá dice una columna que no es la PK, sellar UNA fila sella todas las que compartan ese
     * valor. Le pasó a `skip_markers`, que decía `itemId` cuando su PK ya era `id`
     * (`"<itemId>|<episodeId>"`, una fila por capítulo): un marcador nuevo le ponía reloj nuevo a
     * los de todos los demás capítulos de la serie. Al cambiar la PK de una entidad, este mapa se
     * cambia con ella.
     *
     * Son las seis que viajan por el sync. `live_channels_cache`
     * queda afuera a propósito: es caché reconstruible del catálogo, no datos del usuario, y
     * ponerle triggers de sync mandaría ~1000 filas entre dispositivos para nada.
     */
    private val TABLAS = listOf(
        "items" to "identifier",
        "episodes" to "id",
        "playback" to "episodeId",
        "skip_markers" to "id",
        "live_favorites" to "code",
        "live_recents" to "code",
    )

    /** La hora, en milisegundos, según el reloj de SQLite. */
    private const val AHORA = "CAST(strftime('%s','now') AS INTEGER)*1000"

    /**
     * Los triggers. Sellan la escritura LOCAL pero **respetan un `updatedAt` explícito**, que es lo
     * que escribe el merge cuando adopta una fila del otro aparato: sellarla acá la haría parecer
     * más nueva de lo que es y las dos puntas se la rebotarían para siempre.
     *
     * Cada CREATE va precedido de su propio `DROP TRIGGER IF EXISTS`. Sin eso, `CREATE TRIGGER
     * IF NOT EXISTS` no reemplaza nada: un aparato que ya abrió la base alguna vez se queda para
     * siempre con la definición que tenía creada la primera vez, aunque el texto de acá cambie en
     * una versión nueva de la app. Es justo lo que le pasó a `trg_items_upd`: la versión vieja
     * sellaba con `AHORA` a secas (ver más abajo) y quedó recursando en cualquier aparato que ya
     * la tuviera creada, hasta que se agregó este DROP. Barato: dos triggers por tabla, cuatro
     * tablas, y esto ya corre en cada apertura (ver `ArkivDatabase.SEAL_UPDATED_AT`).
     */
    fun ddl(): List<String> = TABLAS.flatMap { (tabla, pk) ->
        listOf(
            // INSERT local: el writer no puso reloj (quedó en 0) -> sellar.
            "DROP TRIGGER IF EXISTS trg_${tabla}_ins",
            "CREATE TRIGGER IF NOT EXISTS trg_${tabla}_ins AFTER INSERT ON $tabla WHEN NEW.updatedAt = 0 " +
                "BEGIN UPDATE $tabla SET updatedAt = $AHORA WHERE $pk = NEW.$pk; END",
            // UPDATE local: el writer no movió el reloj -> sellar. El merge sí lo mueve, y se salta.
            //
            // El sellado interno NO puede quedarse en `$AHORA` a secas: `$AHORA` tiene resolución de
            // SEGUNDO, así que si esta fila ya tenía `updatedAt` sellado hace menos de un segundo (por
            // ejemplo el propio INSERT de la línea de arriba, o cualquier otra escritura previa en el
            // mismo segundo), el UPDATE de acá escribe el MISMO número. Eso deja `NEW.updatedAt =
            // OLD.updatedAt` otra vez -> el WHEN vuelve a dar verdadero -> el trigger se dispara a sí
            // mismo -> SQLite corta con "too many levels of trigger recursion" (crash real: guardar
            // una temporada de Magis hace `upsertItem` seguido de un UPDATE de badge sobre la misma
            // fila en el mismo segundo). `MAX($AHORA, OLD.updatedAt + 1)` garantiza que el valor
            // CAMBIE siempre respecto al que ya tenía la fila -- si el reloj de segundo no avanzó,
            // igual queda uno más que `OLD.updatedAt`, así que la recursión corta en la segunda
            // pasada. Nunca retrocede (`MAX`, no reemplazo): `updatedAt` es el reloj del que dependen
            // los dos merges LWW y el cursor del push, y un valor que retroceda haría que una fila
            // deje de subir.
            "DROP TRIGGER IF EXISTS trg_${tabla}_upd",
            "CREATE TRIGGER IF NOT EXISTS trg_${tabla}_upd AFTER UPDATE ON $tabla WHEN NEW.updatedAt = OLD.updatedAt " +
                "BEGIN UPDATE $tabla SET updatedAt = MAX($AHORA, OLD.updatedAt + 1) WHERE $pk = NEW.$pk; END",
        )
    }

    /**
     * Sella las filas que ya habían quedado en `updatedAt = 0` por haber nacido sin triggers.
     *
     * Sin esto, los triggers arreglan lo que venga de ahora en adelante pero la biblioteca que la
     * TV ya tenía sigue siendo invisible para la nube para siempre. Idempotente: solo toca los 0.
     */
    fun sellarFilasSinReloj(): List<String> =
        TABLAS.map { (tabla, _) -> "UPDATE $tabla SET updatedAt = $AHORA WHERE updatedAt = 0" }
}
