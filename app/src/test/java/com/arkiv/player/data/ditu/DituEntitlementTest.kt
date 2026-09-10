package com.arkiv.player.data.ditu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DituEntitlementTest {

    private fun userData(vararg flags: Pair<String, Boolean>): JSONObject {
        val ent = JSONObject()
        flags.forEach { (k, v) -> ent.put(k, v) }
        return JSONObject().put(
            "resultObj",
            JSONObject().put("containers", org.json.JSONArray().put(JSONObject().put("entitlement", ent))),
        )
    }

    @Test fun `sin ningun flag no hay bloqueo`() {
        assertNull(DituEntitlement.bloqueo(userData("isGeoBlocked" to false)))
    }

    @Test fun `cada flag tiene su propio mensaje`() {
        assertEquals("solo disponible en Colombia", DituEntitlement.bloqueo(userData("isGeoBlocked" to true)))
        assertEquals("requiere suscripción", DituEntitlement.bloqueo(userData("isChannelNotSubscribed" to true)))
        assertEquals("control parental activo", DituEntitlement.bloqueo(userData("isPCBlocked" to true)))
        assertEquals("contenido OOH bloqueado", DituEntitlement.bloqueo(userData("isContentOOHBlocked" to true)))
        assertEquals("geofence bloqueado", DituEntitlement.bloqueo(userData("isGeofencedBlocked" to true)))
        assertEquals("deportes en blackout", DituEntitlement.bloqueo(userData("isSportBlackoutBlocked" to true)))
        assertEquals("plataforma no permitida", DituEntitlement.bloqueo(userData("isPlatformBlacklisted" to true)))
    }

    /** Con varios activos gana el más informativo, que es el primero de la lista. */
    @Test fun `con varios bloqueos gana el geo`() {
        val d = userData("isPlatformBlacklisted" to true, "isGeoBlocked" to true)
        assertEquals("solo disponible en Colombia", DituEntitlement.bloqueo(d))
    }

    /**
     * Una respuesta sin la forma esperada NO es un bloqueo. Tratarla como tal diría "requiere
     * suscripción" ante un error de red, que manda a la persona a buscar el problema donde no está.
     */
    @Test fun `una respuesta rara no bloquea`() {
        assertNull(DituEntitlement.bloqueo(JSONObject()))
        assertNull(DituEntitlement.bloqueo(JSONObject().put("resultObj", JSONObject())))
        assertNull(DituEntitlement.bloqueo(userData()))
    }

    /** El flag como string "true" no cuenta: solo el booleano de verdad. */
    @Test fun `solo el booleano true bloquea`() {
        val d = JSONObject().put(
            "resultObj",
            JSONObject().put(
                "containers",
                org.json.JSONArray().put(JSONObject().put("entitlement", JSONObject().put("isGeoBlocked", "true"))),
            ),
        )
        assertNull(DituEntitlement.bloqueo(d))
    }
}
