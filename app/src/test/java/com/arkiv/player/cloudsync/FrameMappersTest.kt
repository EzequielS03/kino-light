package com.arkiv.player.cloudsync

import com.arkiv.player.data.db.EpisodeFrameEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    }

    /** Un tombstone no trae archivo: sin `img` no hay nada que bajar. */
    @Test
    fun `un registro sin archivo no deja url de bajada`() {
        val json = JSONObject("""{"episodeId":"ep-1","updatedAt":9,"deleted":1,"id":"r","collectionId":"c","img":""}""")
        assertNull(recordToFrame(json, "https://pb.test").remoteUrl)
    }
}
