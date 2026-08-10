package com.arkiv.player.data.db

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Los triggers que sellan `updatedAt` en cada escritura local.
 *
 * Son el reloj del que dependen los DOS syncs (nube y LAN) para decidir quién gana, y el filtro con
 * el que el push elige qué subir (`getItemsSince(cursor)` = `updatedAt > cursor`). Una fila que
 * queda en `updatedAt = 0` no supera ningún cursor: **no se sube nunca**.
 *
 * Vivían solo dentro de `MIGRATION_6_7`, así que un aparato instalado de cero en una versión
 * posterior nunca los tuvo — Room genera las tablas desde su esquema y los triggers no son parte de
 * él. Medido el 2026-08-10: el Fire TV tenía CERO triggers y 77 de 116 ítems, 1054 de 1806
 * episodios y 7 filas de progreso en `updatedAt = 0`; el celular, que sí migró, tenía los 8 triggers
 * y ninguna fila sin sellar. O sea: todo lo que nacía en el TV era invisible para PocketBase.
 *
 * Se ejecutan contra SQLite de verdad porque son SQL puro: es la única forma de saber si sellan.
 */
class SyncTriggersTest {

    private lateinit var db: Connection

    @Before fun abrir() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.executeUpdate("CREATE TABLE items (identifier TEXT PRIMARY KEY, title TEXT, updatedAt INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0)")
            it.executeUpdate("CREATE TABLE episodes (id TEXT PRIMARY KEY, updatedAt INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0)")
            it.executeUpdate("CREATE TABLE playback (episodeId TEXT PRIMARY KEY, updatedAt INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0)")
            it.executeUpdate("CREATE TABLE skip_markers (itemId TEXT PRIMARY KEY, updatedAt INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0)")
        }
    }

    @After fun cerrar() = db.close()

    private fun aplicar(sentencias: List<String>) =
        db.createStatement().use { st -> sentencias.forEach { st.executeUpdate(it) } }

    private fun relojDe(tabla: String, pk: String, valor: String): Long =
        db.createStatement().use { st ->
            st.executeQuery("SELECT updatedAt FROM $tabla WHERE $pk = '$valor'").use { it.getLong(1) }
        }

    private fun ejecutar(sql: String) = db.createStatement().use { it.executeUpdate(sql) }

    @Test fun una_fila_nueva_queda_sellada_con_la_hora() {
        // EL bug del Fire TV: sin trigger la fila nace en 0 y el push nunca la toma.
        aplicar(SyncTriggers.ddl())
        ejecutar("INSERT INTO items (identifier, title) VALUES ('daima', 'Daima')")
        assertTrue("nació sin reloj", relojDe("items", "identifier", "daima") > 0)
    }

    @Test fun una_fila_que_llega_del_sync_conserva_su_reloj_remoto() {
        // El merge escribe el `updatedAt` del otro aparato a propósito: sellarlo acá rompería el
        // last-write-wins (la fila se vería más nueva de lo que es y rebotaría para siempre).
        aplicar(SyncTriggers.ddl())
        ejecutar("INSERT INTO items (identifier, title, updatedAt) VALUES ('remota', 'R', 12345)")
        assertEquals(12345L, relojDe("items", "identifier", "remota"))
    }

    @Test fun editar_una_fila_le_mueve_el_reloj() {
        aplicar(SyncTriggers.ddl())
        ejecutar("INSERT INTO items (identifier, title, updatedAt) VALUES ('a', 'viejo', 100)")
        ejecutar("UPDATE items SET title = 'nuevo' WHERE identifier = 'a'")
        assertTrue("una edición local tiene que marcarse dirty", relojDe("items", "identifier", "a") > 100)
    }

    @Test fun un_borrado_suave_tambien_mueve_el_reloj() {
        // Si el tombstone no se marca dirty, el borrado no se propaga y el ítem revive.
        aplicar(SyncTriggers.ddl())
        ejecutar("INSERT INTO items (identifier, title, updatedAt) VALUES ('a', 'T', 100)")
        ejecutar("UPDATE items SET deleted = 1 WHERE identifier = 'a'")
        assertTrue(relojDe("items", "identifier", "a") > 100)
    }

    @Test fun el_merge_puede_pisar_el_reloj_sin_que_el_trigger_lo_reescriba() {
        aplicar(SyncTriggers.ddl())
        ejecutar("INSERT INTO items (identifier, title, updatedAt) VALUES ('a', 'T', 100)")
        ejecutar("UPDATE items SET title = 'del otro', updatedAt = 555 WHERE identifier = 'a'")
        assertEquals(555L, relojDe("items", "identifier", "a"))
    }

    @Test fun cubre_las_cuatro_tablas_que_se_sincronizan() {
        aplicar(SyncTriggers.ddl())
        ejecutar("INSERT INTO episodes (id) VALUES ('e1')")
        ejecutar("INSERT INTO playback (episodeId) VALUES ('e1')")
        ejecutar("INSERT INTO skip_markers (itemId) VALUES ('i1')")
        assertTrue(relojDe("episodes", "id", "e1") > 0)
        assertTrue(relojDe("playback", "episodeId", "e1") > 0)
        assertTrue(relojDe("skip_markers", "itemId", "i1") > 0)
    }

    @Test fun aplicarlo_dos_veces_no_falla() {
        // Corre en cada apertura de la base: si no fuera idempotente, la app no abriría más.
        aplicar(SyncTriggers.ddl())
        aplicar(SyncTriggers.ddl())
    }

    @Test fun sella_las_filas_que_ya_habian_quedado_sin_reloj() {
        // Las 77+1054+7 filas que el Fire TV ya tiene en 0: sin esto siguen sin subir para siempre.
        ejecutar("INSERT INTO items (identifier, title, updatedAt) VALUES ('vieja', 'T', 0)")
        ejecutar("INSERT INTO items (identifier, title, updatedAt) VALUES ('ok', 'T', 42)")
        aplicar(SyncTriggers.ddl())
        aplicar(SyncTriggers.sellarFilasSinReloj())
        assertTrue(relojDe("items", "identifier", "vieja") > 0)
        assertEquals("una fila ya sellada no se toca", 42L, relojDe("items", "identifier", "ok"))
    }
}
