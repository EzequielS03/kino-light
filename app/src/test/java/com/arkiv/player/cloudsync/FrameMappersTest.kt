package com.arkiv.player.cloudsync

import com.arkiv.player.data.db.EpisodeFrameEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La fila del frame de ida y de vuelta contra PocketBase. */
class FrameMappersTest {

    @Test
    fun `la fila local viaja con todos sus campos`() {
        val f = frameToFields(
            EpisodeFrameEntity("ep-1", positionMs = 90_000, capturedAt = 7, updatedAt = 42, deleted = 0),
            "acct-1",
        )
        assertEquals("acct-1", f["accountId"])
        assertEquals("ep-1", f["episodeId"])
        assertEquals(90_000L, f["positionMs"])
        assertEquals(42L, f["updatedAt"])
        assertEquals(0, f["deleted"])
    }

    /** `remoteUrl` NO viaja: es estado local (de dónde bajar), no un dato de la fila. */
    @Test
    fun `remoteUrl no se sube`() {
        val f = frameToFields(
            EpisodeFrameEntity("ep-1", 1, 1, 1, 0, remoteUrl = "https://x/y.jpg"), "acct-1",
        )
        assertEquals(false, f.containsKey("remoteUrl"))
    }

    /**
     * En PocketBase OMITIR un campo significa "no lo toques". Un tombstone que no manda `img`
     * dejaba el JPEG en el servidor para siempre, aunque el borrado se propagara a todos lados.
     */
    @Test
    fun `el tombstone manda img en null para que el servidor suelte el jpeg`() {
        val f = frameToFields(EpisodeFrameEntity("ep-1", 0, 0, updatedAt = 42, deleted = 1), "acct-1")
        assertTrue("el campo tiene que viajar", f.containsKey("img"))
        assertNull("y viajar en null, que es lo que borra el archivo", f["img"])
    }

    /**
     * Una fila VIVA no manda `img`: sus bytes viajan aparte, en el multipart de
     * `CloudSyncManager.subirFrame`. Mandar null acá borraría el archivo bueno en cada push que no
     * adjunte imagen (por ejemplo el de una fila adoptada de otro dispositivo).
     */
    @Test
    fun `una fila viva no toca el campo del archivo`() {
        val f = frameToFields(EpisodeFrameEntity("ep-1", 1, 1, updatedAt = 1, deleted = 0), "acct-1")
        assertEquals(false, f.containsKey("img"))
    }

    /** `origenRemoto` es estado local (de dónde vino la fila): tampoco se sube. */
    @Test
    fun `origenRemoto no se sube`() {
        val f = frameToFields(EpisodeFrameEntity("ep-1", 1, 1, 1, 0, origenRemoto = 1), "acct-1")
        assertEquals(false, f.containsKey("origenRemoto"))
    }

    /** El registro remoto arma la URL del archivo con el id de la colección, el del record y el nombre. */
    @Test
    fun `el registro remoto trae de donde bajar el jpeg`() {
        val json = JSONObject(
            """{"episodeId":"ep-1","positionMs":5000,"updatedAt":9,"deleted":0,
                "id":"rec1","collectionId":"col1","img":"frame.jpg"}""",
        )
        val e = recordToFrame(json, "https://pb.test")
        assertEquals("ep-1", e.episodeId)
        assertEquals(5000L, e.positionMs)
        assertEquals(9L, e.updatedAt)
        assertEquals("https://pb.test/api/files/col1/rec1/frame.jpg", e.remoteUrl)
        assertEquals("la fila queda marcada como adoptada, para no re-subir sus bytes", 1, e.origenRemoto)
    }

    /** Un tombstone no trae archivo: sin `img` no hay nada que bajar. */
    @Test
    fun `un registro sin archivo no deja url de bajada`() {
        val json = JSONObject("""{"episodeId":"ep-1","updatedAt":9,"deleted":1,"id":"r","collectionId":"c","img":""}""")
        assertNull(recordToFrame(json, "https://pb.test").remoteUrl)
    }
}
