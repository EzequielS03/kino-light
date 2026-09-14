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
 * Eran el reloj del que dependían los DOS syncs (nube y LAN, los dos borrados de este branch) para
 * decidir quién ganaba, y el filtro con el que el push elegía qué subir (`updatedAt > cursor`; el
 * DAO que leía ese cursor también se borró). El trigger sigue vigente porque las columnas
 * `updatedAt`/`deleted` se quedan hasta el audit de columnas de la Fase 3.
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
            // El mismo esquema que genera Room: la PK es `id` ("<itemId>|<episodeId>"), no `itemId`
            // -- una serie tiene una fila por capítulo más la de la serie entera.
            it.executeUpdate(
                "CREATE TABLE skip_markers (id TEXT PRIMARY KEY, itemId TEXT NOT NULL, episodeId TEXT NOT NULL DEFAULT '', " +
                    "updatedAt INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0)",
            )
            // Task 10: favoritos y recientes de TV en vivo, las dos tablas que se sumaron a
            // SyncTriggers.TABLAS. `live_recents` no lleva `deleted` a propósito -- ver
            // LiveRecentEntity -- así que acá tampoco, para que el esquema de este test siga
            // siendo fiel al real.
            it.executeUpdate("CREATE TABLE live_favorites (code TEXT PRIMARY KEY, updatedAt INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0)")
            it.executeUpdate("CREATE TABLE live_recents (code TEXT PRIMARY KEY, updatedAt INTEGER NOT NULL DEFAULT 0)")
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

    @Test fun cubre_las_seis_tablas_que_se_sincronizan() {
        aplicar(SyncTriggers.ddl())
        ejecutar("INSERT INTO episodes (id) VALUES ('e1')")
        ejecutar("INSERT INTO playback (episodeId) VALUES ('e1')")
        ejecutar("INSERT INTO skip_markers (id, itemId, episodeId) VALUES ('i1|', 'i1', '')")
        ejecutar("INSERT INTO live_favorites (code) VALUES ('c1')")
        ejecutar("INSERT INTO live_recents (code) VALUES ('c1')")
        assertTrue(relojDe("episodes", "id", "e1") > 0)
        assertTrue(relojDe("playback", "episodeId", "e1") > 0)
        assertTrue(relojDe("skip_markers", "id", "i1|") > 0)
        assertTrue(relojDe("live_favorites", "code", "c1") > 0)
        assertTrue(relojDe("live_recents", "code", "c1") > 0)
    }

    @Test fun sellar_un_marcador_no_le_mueve_el_reloj_a_los_otros_capitulos_de_la_serie() {
        // El mapa de tablas declaraba `skip_markers to "itemId"`, que ya no es la PK: el trigger
        // sellaba con `WHERE itemId = NEW.itemId`, o sea TODOS los marcadores de la serie de una.
        // Un solo marcador nuevo sin reloj le ponía hora nueva a los de todos los demás capítulos
        // y los mandaba a subir como si se acabaran de editar (y en el merge LWW, a ganarle a lo
        // que hubiera del otro lado).
        aplicar(SyncTriggers.ddl())
        ejecutar("INSERT INTO skip_markers (id, itemId, episodeId, updatedAt) VALUES ('daima|e1', 'daima', 'e1', 42)")
        ejecutar("INSERT INTO skip_markers (id, itemId, episodeId) VALUES ('daima|e2', 'daima', 'e2')")
        assertEquals("el marcador del otro capítulo no se tocó", 42L, relojDe("skip_markers", "id", "daima|e1"))
        assertTrue("el que nació sin reloj sí se sella", relojDe("skip_markers", "id", "daima|e2") > 0)
    }

    @Test fun aplicarlo_dos_veces_no_falla() {
        // Corre en cada apertura de la base: si no fuera idempotente, la app no abriría más.
        aplicar(SyncTriggers.ddl())
        aplicar(SyncTriggers.ddl())
    }

    @Test fun dos_escrituras_en_el_mismo_segundo_el_reloj_siempre_avanza() {
        // El crash real (Fire TV, 2026-08-10): `ArkivRepository.addMagisSeason` hacía `upsertItem`
        // (el INSERT sella con AHORA) y, en el mismo segundo, un UPDATE que no mueve el reloj
        // (`markEpisodesSeen`, el badge). `AHORA` tiene resolución de SEGUNDO: el UPDATE de
        // adentro del trigger volvía a escribir el mismo número, `NEW.updatedAt = OLD.updatedAt`
        // daba verdadero otra vez, y el trigger se disparaba a sí mismo hasta que SQLite cortaba
        // con "too many levels of trigger recursion" -- la app moría después de guardar pero antes
        // de navegar al reproductor.
        //
        // Se fuerza el mismo segundo insertando con `updatedAt` = AHORA (no un valor fijo del
        // pasado, que es lo que hacen los demás tests de este archivo) y actualizando enseguida:
        // entre las dos sentencias pasan microsegundos, así que el reloj de segundo real todavía no
        // avanzó.
        //
        // Acá NO se ve el `SQLITE_ERROR` textual del crash: Android trae `recursive_triggers` en ON,
        // pero xerial/sqlite-jdbc (lo que corre este test, verificado con `PRAGMA
        // recursive_triggers` = 0) lo trae en OFF por default, así que la re-invocación del trigger
        // sobre su propia escritura queda desactivada acá y no hay recursión de verdad que contar.
        // Lo que SÍ es igual en los dos entornos es el defecto de fondo -- el `UPDATE` de adentro del
        // trigger escribe el mismo valor que ya tenía la fila -- y es eso lo que este test verifica:
        // con el trigger viejo, `relojDe(...)` después del UPDATE queda IGUAL a `sellado` (el
        // `assertTrue` de abajo falla); con el nuevo, siempre avanza.
        aplicar(SyncTriggers.ddl())
        ejecutar(
            "INSERT INTO items (identifier, title, updatedAt) VALUES " +
                "('a', 'viejo', CAST(strftime('%s','now') AS INTEGER)*1000)",
        )
        val sellado = relojDe("items", "identifier", "a")
        ejecutar("UPDATE items SET title = 'nuevo' WHERE identifier = 'a'")
        assertTrue(
            "la segunda escritura en el mismo segundo tiene que AVANZAR el reloj, no repetirlo",
            relojDe("items", "identifier", "a") > sellado,
        )
    }

    @Test fun un_trigger_viejo_ya_creado_se_reemplaza_por_el_nuevo() {
        // `CREATE TRIGGER IF NOT EXISTS` no reemplaza nada: sin el `DROP TRIGGER IF EXISTS` que
        // ahora precede a cada CREATE, un aparato que ya había abierto la base con el trigger
        // recursivo (el de antes de este fix) se hubiera quedado con esa definición para siempre.
        // Acá se simula ese aparato: se crea a mano el trigger VIEJO (el que sella con AHORA a
        // secas) y se verifica que aplicar `ddl()` de nuevo -- lo que pasa en cada apertura de la
        // base, ver `ArkivDatabase.SEAL_UPDATED_AT` -- lo deja con el nuevo.
        ejecutar(
            "CREATE TRIGGER trg_items_upd AFTER UPDATE ON items WHEN NEW.updatedAt = OLD.updatedAt " +
                "BEGIN UPDATE items SET updatedAt = CAST(strftime('%s','now') AS INTEGER)*1000 " +
                "WHERE identifier = NEW.identifier; END",
        )
        aplicar(SyncTriggers.ddl())
        ejecutar(
            "INSERT INTO items (identifier, title, updatedAt) VALUES " +
                "('a', 'viejo', CAST(strftime('%s','now') AS INTEGER)*1000)",
        )
        val sellado = relojDe("items", "identifier", "a")
        ejecutar("UPDATE items SET title = 'nuevo' WHERE identifier = 'a'")
        assertTrue(relojDe("items", "identifier", "a") > sellado)
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
