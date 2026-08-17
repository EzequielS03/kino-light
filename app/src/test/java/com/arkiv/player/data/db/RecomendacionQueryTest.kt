package com.arkiv.player.data.db

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * La consulta que expone las recomendaciones vigentes de la cuenta (fila "Para ti" del inicio):
 * ordenadas por `orden` -- lo que decidió el gateway -- y sin lo que ya quedó marcado como tombstone
 * (`deleted`). Ver [RecomendacionDao.observeVigentes].
 *
 * Se ejecuta contra SQLite de verdad -- mismo criterio que [SyncTriggersTest] -- porque es SQL puro
 * y este módulo no tiene infraestructura de Room (ni Robolectric) en los tests unitarios de la JVM.
 * El `CREATE TABLE` de acá tiene que quedarse en sincro con `MIGRATION_24_25` de [ArkivDatabase] y
 * el `@Query` de acá con el de [RecomendacionDao.observeVigentes] -- no hay forma automática de
 * comprobarlo, así que cualquier cambio en uno tiene que reflejarse en el otro.
 */
class RecomendacionQueryTest {

    private lateinit var db: Connection

    @Before fun abrir() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.executeUpdate(
                "CREATE TABLE recomendaciones (" +
                    "id TEXT NOT NULL PRIMARY KEY, tmdbId INTEGER NOT NULL, tipo TEXT NOT NULL, " +
                    "titulo TEXT NOT NULL, posterUrl TEXT NOT NULL, porque TEXT NOT NULL, " +
                    "ref TEXT NOT NULL, orden INTEGER NOT NULL, generadoAt INTEGER NOT NULL, " +
                    "updatedAt INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0)",
            )
        }
    }

    @After fun cerrar() = db.close()

    private fun insertar(id: String, orden: Int, deleted: Int = 0) {
        db.createStatement().use {
            it.executeUpdate(
                "INSERT INTO recomendaciones " +
                    "(id, tmdbId, tipo, titulo, posterUrl, porque, ref, orden, generadoAt, updatedAt, deleted) " +
                    "VALUES ('$id', 1, 'movie', 'T', '', '', 'ref', $orden, 0, 0, $deleted)",
            )
        }
    }

    /** Misma consulta que [RecomendacionDao.observeVigentes]. */
    private fun vigentes(): List<String> =
        db.createStatement().use { st ->
            st.executeQuery("SELECT id FROM recomendaciones WHERE deleted = 0 ORDER BY orden ASC").use { rs ->
                val out = mutableListOf<String>()
                while (rs.next()) out.add(rs.getString("id"))
                out
            }
        }

    @Test fun devuelve_ordenado_por_orden() {
        insertar("c", orden = 2)
        insertar("a", orden = 0)
        insertar("b", orden = 1)
        assertEquals(listOf("a", "b", "c"), vigentes())
    }

    @Test fun excluye_las_borradas() {
        insertar("viva", orden = 0)
        insertar("tumba", orden = 1, deleted = 1)
        assertEquals("el tombstone no puede reaparecer en la fila 'Para ti'", listOf("viva"), vigentes())
    }

    @Test fun una_borrada_mas_nueva_que_gano_el_lww_deja_de_aparecer() {
        // Simula lo que hace CloudSyncManager.mergeRecomendacion tras un pull: la fila YA estaba
        // viva localmente, y el upsert la deja con deleted=1 porque el remoto (más nuevo) ganó.
        insertar("rec1", orden = 0)
        db.createStatement().use {
            it.executeUpdate("UPDATE recomendaciones SET deleted = 1, updatedAt = 999 WHERE id = 'rec1'")
        }
        assertEquals(emptyList<String>(), vigentes())
    }
}
