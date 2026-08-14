package com.arkiv.player.data.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El PORQUÉ que manda el gateway cuando algo falla, para que no se pierda en el camino.
 *
 * El 2026-08-14 ningún canal de TV en vivo abría y el log del Fire TV decía, para todos:
 * `live: el gateway respondio 502`. Nada más. El motivo real —el portal contestando
 * `aaa100028 / 未登录!`, o sea que el directo exige cuenta de Magis vinculada mientras que el VOD
 * anda con la anónima— viajaba en el cuerpo de la respuesta, que se leía y se tiraba.
 *
 * Hubo que entrar al contenedor del servidor y reproducir la llamada a mano para enterarse de algo
 * que ya estaba escrito en la respuesta que la app tenía en la mano.
 */
class MotivoDelGatewayTest {

    @Test fun `saca el detail de un error de FastAPI`() {
        assertEquals(
            "el portal no dio direcciones para cyx-RCNHD (error aaa100028)",
            motivoDelGateway("""{"detail":"el portal no dio direcciones para cyx-RCNHD (error aaa100028)"}"""),
        )
    }

    /** El `detail` puede venir como objeto (así lo manda la capa de identidad). */
    @Test fun `un detail que es objeto se muestra entero`() {
        assertEquals(
            """{"codigo":"sesion_invalida","mensaje":"volve a entrar"}""",
            motivoDelGateway("""{"detail":{"codigo":"sesion_invalida","mensaje":"volve a entrar"}}"""),
        )
    }

    /** Un cuerpo que no es JSON igual sirve: es lo único que hay para saber qué pasó. */
    @Test fun `un cuerpo que no es JSON se devuelve tal cual`() {
        assertEquals("502 Bad Gateway (nginx)", motivoDelGateway("502 Bad Gateway (nginx)"))
    }

    @Test fun `un cuerpo vacio no aporta motivo`() {
        assertEquals("", motivoDelGateway(""))
        assertEquals("", motivoDelGateway("   "))
    }

    /** Un JSON sin `detail` tampoco inventa nada: se muestra crudo. */
    @Test fun `un json sin detail se muestra crudo`() {
        assertEquals("""{"otra":"cosa"}""", motivoDelGateway("""{"otra":"cosa"}"""))
    }

    /**
     * Un cuerpo largo no puede inundar el log ni la excepción. El corte es generoso: los mensajes
     * que importan (los del portal, con su código) entran holgados.
     */
    @Test fun `un cuerpo enorme se recorta`() {
        val largo = motivoDelGateway("x".repeat(1000))
        assert(largo.length <= MOTIVO_MAX + 1) { "quedó en ${largo.length}" }
        assert(largo.endsWith("…")) { largo }
    }
}
