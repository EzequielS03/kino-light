package com.arkiv.player.data.db

/**
 * El DDL que mantiene `updatedAt` al día en las tablas que se sincronizan.
 *
 * `updatedAt` es el reloj del que dependen los dos syncs: el merge decide con él quién gana
 * (`cloudsync.LwwMerge`, `sync.SyncMerge`) y el push elige qué subir con `updatedAt > cursor`. Una
 * fila que se queda en 0 **no supera ningún cursor y no se sube nunca**.
 *
 * Vivía solo dentro de `MIGRATION_6_7`, y ahí estaba el problema: Room crea las tablas desde su
 * esquema generado, y los triggers no son parte de ese esquema. Un aparato **instalado de cero** en
 * una versión posterior nunca corre esa migración, así que nunca tuvo los triggers y todo lo que
 * creaba localmente nacía —y se quedaba— en `updatedAt = 0`. Medido el 2026-08-10: el Fire TV tenía
 * cero triggers y 77 de 116 ítems, 1054 de 1806 episodios y 7 filas de progreso sin sellar; el
 * celular, que sí venía migrando desde v6, los tenía todos. Por eso lo que se veía o guardaba en la
 * TV no llegaba nunca al celular.
 *
 * Por eso [ddl] se aplica en CADA apertura de la base y no en una migración: es idempotente
 * (`IF NOT EXISTS`) y así vale igual para el que migra y para el que instala de cero.
 */
object SyncTriggers {

    /** Tabla → su clave primaria. Son las cuatro que viajan por el sync. */
    private val TABLAS = listOf(
        "items" to "identifier",
        "episodes" to "id",
        "playback" to "episodeId",
        "skip_markers" to "itemId",
    )

    /** La hora, en milisegundos, según el reloj de SQLite. */
    private const val AHORA = "CAST(strftime('%s','now') AS INTEGER)*1000"

    /**
     * Los triggers. Sellan la escritura LOCAL pero **respetan un `updatedAt` explícito**, que es lo
     * que escribe el merge cuando adopta una fila del otro aparato: sellarla acá la haría parecer
     * más nueva de lo que es y las dos puntas se la rebotarían para siempre.
     */
    fun ddl(): List<String> = TABLAS.flatMap { (tabla, pk) ->
        listOf(
            // INSERT local: el writer no puso reloj (quedó en 0) -> sellar.
            "CREATE TRIGGER IF NOT EXISTS trg_${tabla}_ins AFTER INSERT ON $tabla WHEN NEW.updatedAt = 0 " +
                "BEGIN UPDATE $tabla SET updatedAt = $AHORA WHERE $pk = NEW.$pk; END",
            // UPDATE local: el writer no movió el reloj -> sellar. El merge sí lo mueve, y se salta.
            "CREATE TRIGGER IF NOT EXISTS trg_${tabla}_upd AFTER UPDATE ON $tabla WHEN NEW.updatedAt = OLD.updatedAt " +
                "BEGIN UPDATE $tabla SET updatedAt = $AHORA WHERE $pk = NEW.$pk; END",
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
