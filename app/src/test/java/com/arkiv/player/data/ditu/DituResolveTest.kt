package com.arkiv.player.data.ditu

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DituResolveTest {

    private val LIC = "https://middleware.ditu.caracoltv.com/AGL/1.6/A/ENG/ANDROID/ALL/CONTENT/LICENSE"

    private fun fakeListo(): FakeDituCliente {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/DETAIL/VOD/42", """
        {"resultObj":{"containers":[{"assets":[{"assetType":"MASTER","assetId":7}]}]}}
        """)
        fake.responde("CONTENT/USERDATA/VOD/42", """{"resultObj":{"containers":[{"entitlement":{}}]}}""")
        fake.responde("CONTENT/VIDEOURL/VOD/42/7", """{"resultObj":{"src":"https://cdn/pelicula.mpd"}}""")
        fake.token = "tok999"
        return fake
    }

    @Test fun `una pelicula se resuelve en tres pasos y en orden`() = runTest {
        val fake = fakeListo()

        val play = DituResolve(fake).vod(DituRef("42", "VOD"))

        assertEquals(
            listOf("CONTENT/DETAIL/VOD/42", "CONTENT/USERDATA/VOD/42", "CONTENT/VIDEOURL/VOD/42/7"),
            fake.llamadas.map { it.first },
        )
        assertEquals("https://cdn/pelicula.mpd", play.url)
        assertEquals("application/dash+xml", play.mime)
        assertEquals("ditu", play.kind)
    }

    /** La licencia sin la cookie responde 500: el token TIENE que llegar como header de licencia. */
    @Test fun `la cookie del token viaja como header de la licencia`() = runTest {
        val play = DituResolve(fakeListo()).vod(DituRef("42", "VOD"))

        assertEquals(LIC, play.drmLicenseUrl)
        assertEquals(mapOf("Cookie" to "playback_token=tok999"), play.drmLicenseHeaders)
    }

    /** Sin token igual se devuelve algo reproducible: el que falla después es el servidor de
     *  licencias, y su 500 se diagnostica mejor que un error nuestro inventado antes. */
    @Test fun `sin token no se manda header de cookie`() = runTest {
        val fake = fakeListo()
        fake.token = ""

        val play = DituResolve(fake).vod(DituRef("42", "VOD"))

        assertEquals(LIC, play.drmLicenseUrl)
        assertTrue(play.drmLicenseHeaders.isEmpty())
    }

    @Test fun `un bloqueo de entitlement corta antes de pedir la URL`() = runTest {
        val fake = fakeListo()
        fake.responde("CONTENT/USERDATA/VOD/42", """
        {"resultObj":{"containers":[{"entitlement":{"isGeoBlocked":true}}]}}
        """)

        val e = runCatching { DituResolve(fake).vod(DituRef("42", "VOD")) }.exceptionOrNull()

        assertTrue(e is DituException)
        assertTrue(e!!.message!!.contains("solo disponible en Colombia"))
        assertTrue("no debió pedir la URL", fake.llamadas.none { it.first.startsWith("CONTENT/VIDEOURL") })
    }

    @Test fun `sin assetId no se puede resolver`() = runTest {
        val fake = fakeListo()
        fake.responde("CONTENT/DETAIL/VOD/42", """{"resultObj":{"containers":[{"assets":[]}]}}""")

        val e = runCatching { DituResolve(fake).vod(DituRef("42", "VOD")) }.exceptionOrNull()
        assertTrue(e is DituException)
    }

    @Test fun `sin src no se puede reproducir`() = runTest {
        val fake = fakeListo()
        fake.responde("CONTENT/VIDEOURL/VOD/42/7", """{"resultObj":{}}""")

        val e = runCatching { DituResolve(fake).vod(DituRef("42", "VOD")) }.exceptionOrNull()
        assertTrue(e is DituException)
    }

    /** Un BUNDLE no es reproducible: se resuelve su primer capítulo con assetId. */
    @Test fun `un BUNDLE resuelve su primer capitulo`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/DETAIL/BUNDLE/99", """
        {"resultObj":{"containers":[{"containers":[
          {"id":"sinasset","metadata":{}},
          {"id":"e2","metadata":{},"assets":[{"assetType":"MASTER","assetId":5}]}
        ]}]}}
        """)
        fake.responde("CONTENT/USERDATA/VOD/e2", """{"resultObj":{"containers":[{"entitlement":{}}]}}""")
        fake.responde("CONTENT/VIDEOURL/VOD/e2/5", """{"resultObj":{"src":"https://cdn/e2.mpd"}}""")

        val play = DituResolve(fake).vod(DituRef("99", "BUNDLE"))

        assertEquals("https://cdn/e2.mpd", play.url)
    }

    /** Un GROUP_OF_BUNDLES resuelve el primer capítulo del primer bundle hijo. */
    @Test fun `un GROUP_OF_BUNDLES resuelve el primer capitulo de su primer bundle`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("TRAY/SEARCH/VOD", """
        {"resultObj":{"containers":[{"id":"b1"},{"id":"b2"}]}}
        """)
        fake.responde("CONTENT/DETAIL/BUNDLE/b1", """
        {"resultObj":{"containers":[{"containers":[
          {"id":"ep1","metadata":{},"assets":[{"assetType":"MASTER","assetId":11}]}
        ]}]}}
        """)
        fake.responde("CONTENT/USERDATA/VOD/ep1", """{"resultObj":{"containers":[{"entitlement":{}}]}}""")
        fake.responde("CONTENT/VIDEOURL/VOD/ep1/11", """{"resultObj":{"src":"https://cdn/grupo.mpd"}}""")
        fake.token = "tokgrupo"

        val play = DituResolve(fake).vod(DituRef("999", "GROUP_OF_BUNDLES"))

        assertEquals("https://cdn/grupo.mpd", play.url)
        // Verificar que se pidió la lista de bundles hijos con los parámetros correctos
        val trayCalls = fake.llamadas.filter { it.first == "TRAY/SEARCH/VOD" }
        assertTrue(trayCalls.isNotEmpty())
        val params = trayCalls.first().second
        assertEquals("999", params["filter_parentId"])
        assertEquals("BUNDLE", params["filter_contentType"])
    }

    /** Si el primer bundle no tiene capítulos reproducibles, usa el segundo. */
    @Test fun `un GROUP_OF_BUNDLES salta bundles sin capitulos reproducibles`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("TRAY/SEARCH/VOD", """
        {"resultObj":{"containers":[{"id":"b1"},{"id":"b2"}]}}
        """)
        // Primer bundle sin capítulos reproducibles
        fake.responde("CONTENT/DETAIL/BUNDLE/b1", """
        {"resultObj":{"containers":[{"containers":[
          {"id":"ep1","metadata":{}}
        ]}]}}
        """)
        // Segundo bundle sí tiene
        fake.responde("CONTENT/DETAIL/BUNDLE/b2", """
        {"resultObj":{"containers":[{"containers":[
          {"id":"ep2","metadata":{},"assets":[{"assetType":"MASTER","assetId":22}]}
        ]}]}}
        """)
        fake.responde("CONTENT/USERDATA/VOD/ep2", """{"resultObj":{"containers":[{"entitlement":{}}]}}""")
        fake.responde("CONTENT/VIDEOURL/VOD/ep2/22", """{"resultObj":{"src":"https://cdn/segundo.mpd"}}""")

        val play = DituResolve(fake).vod(DituRef("999", "GROUP_OF_BUNDLES"))

        assertEquals("https://cdn/segundo.mpd", play.url)
    }

    // --- en vivo ---------------------------------------------------------------

    /** El vivo son DOS pasos: el assetId ya vino con el canal, así que no hay DETAIL. */
    @Test fun `un canal en vivo se resuelve en dos pasos`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/USERDATA/LIVE/7", """{"resultObj":{"containers":[{"entitlement":{}}]}}""")
        fake.responde("CONTENT/VIDEOURL/LIVE/7/22", """{"resultObj":{"src":"https://cdn/vivo.mpd"}}""")
        fake.token = "tokvivo"

        val play = DituResolve(fake).vivo(DituCanal(7, "Caracol", "l.png", 22))

        assertEquals(listOf("CONTENT/USERDATA/LIVE/7", "CONTENT/VIDEOURL/LIVE/7/22"), fake.llamadas.map { it.first })
        assertEquals("https://cdn/vivo.mpd", play.url)
        assertEquals(mapOf("Cookie" to "playback_token=tokvivo"), play.drmLicenseHeaders)
    }

    @Test fun `un canal bloqueado dice por que`() = runTest {
        val fake = FakeDituCliente()
        fake.responde("CONTENT/USERDATA/LIVE/7", """
        {"resultObj":{"containers":[{"entitlement":{"isChannelNotSubscribed":true}}]}}
        """)

        val e = runCatching { DituResolve(fake).vivo(DituCanal(7, "C", "", 22)) }.exceptionOrNull()

        assertTrue(e!!.message!!.contains("requiere suscripción"))
    }
}
