package com.arkiv.player.miniaturas

import com.arkiv.player.data.db.EpisodeFrameEntity
import com.arkiv.player.pocketbase.DeviceAuthManager
import com.arkiv.player.pocketbase.DeviceIdentity
import com.arkiv.player.pocketbase.FakeDeviceStore
import com.arkiv.player.pocketbase.PocketBaseClient
import com.arkiv.player.pocketbase.cuentaApiSinUsarParaBootstrap
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * El bajador del JPEG remoto: baja SOLO lo que se va a pintar, no resucita frames borrados y se
 * planta si la sesión cambia a mitad de pasada.
 *
 * Corre contra un PocketBase de mentira (MockWebServer) porque los dos pasos del archivo protegido
 * —file-token y después `?token=`— son la parte más delicada de la fase 2.
 */
class BajadorDeFramesTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var pb: FakePocketBase
    private lateinit var dao: FakeEpisodeFrameDao
    private lateinit var almacen: AlmacenDeFrames
    private lateinit var deviceAuth: DeviceAuthManager

    /**
     * Rutea las cuatro cosas que se le piden al servidor en estos tests. No usa la cola de
     * `enqueue` porque hace falta un GANCHO en la bajada del archivo: es ahí, con la cola ya leída
     * y los bytes en vuelo, donde caen el borrado y el logout que estos tests reproducen.
     */
    private class FakePocketBase : Dispatcher() {
        /** Corre cuando llega el pedido del JPEG, ANTES de responderlo. */
        var alBajarArchivo: (() -> Unit)? = null

        /** `episodeId` cuyo archivo responde 404 (el resto responde bytes). */
        var falla: String? = null

        val jpeg = byteArrayOf(1, 2, 3, 4)

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                path.startsWith("/api/files/token") -> MockResponse().setBody("""{"token":"ftok-1"}""")
                path.startsWith("/api/files/") -> {
                    alBajarArchivo?.invoke()
                    if (falla != null && path.contains(falla!!)) MockResponse().setResponseCode(404)
                    else MockResponse().setBody(Buffer().write(jpeg)).setHeader("Content-Type", "image/jpeg")
                }
                path.contains("auth-with-password") ->
                    MockResponse().setBody("""{"token":"dtok","record":{"id":"devrec"}}""")
                // PATCH de devices: el switchAccount con el que se simula el cambio de identidad.
                else -> MockResponse().setBody("""{"id":"devrec"}""")
            }
        }
    }

    @Before fun abrir() {
        server = MockWebServer()
        pb = FakePocketBase()
        server.dispatcher = pb
        server.start()
        dao = FakeEpisodeFrameDao()
        almacen = AlmacenDeFrames(temp.newFolder("frames"))
        val deviceStore = FakeDeviceStore(DeviceIdentity("acc-1", "dev-1", "dev-1@arkiv.local", "pw12345678", "phone"))
        val deviceClient = cliente()
        deviceAuth = DeviceAuthManager(deviceClient, deviceStore, cuentaApiSinUsarParaBootstrap(deviceClient, deviceStore))
        runBlocking { deviceAuth.ensureBootstrapped() }
    }

    @After fun cerrar() = server.shutdown()

    private fun cliente() = PocketBaseClient(baseUrl = server.url("/").toString().trimEnd('/'))

    private fun bajador() = BajadorDeFrames(almacen, dao, cliente(), deviceAuth)

    private fun filaPendiente(episodeId: String, updatedAt: Long) {
        dao.filas[episodeId] = EpisodeFrameEntity(
            episodeId = episodeId, positionMs = 1000, capturedAt = updatedAt, updatedAt = updatedAt,
            deleted = 0, remoteUrl = "${server.url("/")}api/files/col/rec-$episodeId/frame.jpg",
            origenRemoto = 1,
        )
    }

    @Test fun `baja solo los capitulos que se piden, no la cola entera`() = runBlocking {
        filaPendiente("ep-1", 10)
        filaPendiente("ep-2", 20)
        filaPendiente("ep-3", 30)

        bajador().bajarPendientes(listOf("ep-2"))

        assertNotNull("el capítulo pedido se baja", almacen.rutaSiExiste("ep-2"))
        assertNull("lo que no se va a pintar no se baja", almacen.rutaSiExiste("ep-1"))
        assertNull(almacen.rutaSiExiste("ep-3"))
        assertNull("la fila bajada sale de la cola", dao.filas.getValue("ep-2").remoteUrl)
        assertNotNull("las otras siguen pendientes", dao.filas.getValue("ep-1").remoteUrl)
    }

    @Test fun `los bytes bajados son los que quedan en disco`() = runBlocking {
        filaPendiente("ep-1", 10)

        bajador().bajarPendientes(listOf("ep-1"))

        val ruta = almacen.rutaSiExiste("ep-1")
        assertNotNull(ruta)
        assertTrue(pb.jpeg.contentEquals(java.io.File(ruta!!).readBytes()))
    }

    /**
     * EL hallazgo: entre leer la cola y publicar el archivo puede correr `destruir` (el capítulo
     * pasó el 60%, o llegó el `watched` del otro aparato). Reescribiendo la copia VIEJA de la fila,
     * el JPEG recién borrado volvía y el tombstone quedaba pisado con `deleted = 0` y un `updatedAt`
     * más viejo que el del borrado — y como el cursor de push ya había pasado ese valor, la fila
     * resucitada no se corregía nunca.
     */
    @Test fun `no resucita un frame que se borro durante la pasada`() = runBlocking {
        filaPendiente("ep-1", 10)
        pb.alBajarArchivo = {
            runBlocking { DestructorDeFrames(almacen, dao) { 500L }.destruir("ep-1") }
        }

        bajador().bajarPendientes(listOf("ep-1"))

        val fila = dao.filas.getValue("ep-1")
        assertEquals("la fila tiene que seguir siendo tombstone", 1, fila.deleted)
        assertEquals("y conservar el updatedAt del borrado, no el de la copia vieja", 500L, fila.updatedAt)
        assertNull("el JPEG recién borrado no puede volver a disco", almacen.rutaSiExiste("ep-1"))
    }

    /** Lo mismo pero con una captura local NUEVA: los bytes viejos no pueden quedar publicados. */
    @Test fun `no pisa una captura local mas nueva`() = runBlocking {
        filaPendiente("ep-1", 10)
        pb.alBajarArchivo = {
            dao.filas["ep-1"] = dao.filas.getValue("ep-1").copy(updatedAt = 900, remoteUrl = null)
        }

        bajador().bajarPendientes(listOf("ep-1"))

        assertEquals("la fila nueva queda intacta", 900L, dao.filas.getValue("ep-1").updatedAt)
        assertNull("y el JPEG viejo no queda publicado bajo esa fila", almacen.rutaSiExiste("ep-1"))
    }

    /**
     * Logout a mitad de pasada: `destruirTodo` ya vació `filesDir/frames` y la tabla. Seguir
     * escribiendo recrearía escenas de la identidad anterior (privacidad) y reinsertaría filas que
     * después se empujan con el accountId NUEVO — o sea, la escena de la cuenta A subida a la B.
     */
    @Test fun `aborta si cambia la cuenta a mitad de pasada`() = runBlocking {
        filaPendiente("ep-1", 10)
        filaPendiente("ep-2", 20)
        pb.alBajarArchivo = { runBlocking { deviceAuth.switchAccount("acc-2") } }

        bajador().bajarPendientes(listOf("ep-1", "ep-2"))

        assertEquals("el gancho tiene que haber corrido", "acc-2", deviceAuth.session.value?.accountId)
        assertNull("nada de la cuenta vieja puede quedar en disco", almacen.rutaSiExiste("ep-1"))
        assertNull(almacen.rutaSiExiste("ep-2"))
        assertTrue(
            "ninguna fila de la cuenta vieja se marca como bajada",
            dao.filas.values.all { it.remoteUrl != null },
        )
        assertTrue(
            "no queda ningún archivo suelto (ni temporales)",
            temp.root.walkTopDown().none { it.isFile },
        )
    }

    /** Un 404 en una fila no puede tumbar el resto de la cola. */
    @Test fun `una fila que falla no frena a las demas`() = runBlocking {
        filaPendiente("ep-1", 10)
        filaPendiente("ep-2", 20)
        pb.falla = "rec-ep-1"

        bajador().bajarPendientes(listOf("ep-1", "ep-2"))

        assertNotNull("la segunda fila igual se baja", almacen.rutaSiExiste("ep-2"))
        assertNull(almacen.rutaSiExiste("ep-1"))
        assertNotNull("la que falló sigue en la cola para la próxima pasada", dao.filas.getValue("ep-1").remoteUrl)
    }

    /** Sin nada que pintar no se pide ni el file-token. */
    @Test fun `sin capitulos pedidos no toca la red`() = runBlocking {
        filaPendiente("ep-1", 10)

        bajador().bajarPendientes(emptyList())

        assertEquals("solo el bootstrap de la sesión", 1, server.requestCount)
    }
}
