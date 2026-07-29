package com.arkiv.player.data.catalog.web

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Valida el pipeline completo asset JSON -> WebSourceRegistry -> WebHtmlParser -> WebResult
 * usando el `web_sources.json` REAL empaquetado en el APK (copiado a test resources) y un
 * fixture HTML SINTÉTICO por canal (`web/<id>_movies.html`, portado de los selectores de Alfa;
 * NO es HTML real bajado del sitio). Esta prueba certifica el CABLEADO del parser (que la
 * definición parsea y sus selectores extraen filas de un HTML consistente con ellos), NO que los
 * selectores matcheen el sitio en producción — eso se valida contra el sitio real en el dispositivo.
 *
 * Es data-driven: recorre todas las definiciones del asset y valida las que tengan fixture.
 */
class WebFixtureTest {
    private fun res(path: String): String? =
        this::class.java.classLoader!!.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }

    @Test fun `cada canal con fixture parsea filas con title y pageUrl`() {
        val defs = WebSourceRegistry.parseDefinitions(res("web/bundled_web_sources.json")!!)
        assertTrue("el asset debe parsear al menos 1 definición", defs.isNotEmpty())

        val fallos = mutableListOf<String>()
        var validados = 0
        for (def in defs) {
            val html = res("web/${def.id}_movies.html") ?: continue // canal sin fixture: se valida en dispositivo
            validados++
            val rows = WebHtmlParser.parse(def, html, "movie")
            when {
                rows.isEmpty() ->
                    fallos += "${def.id}: 0 filas (selectores/regex no matchean su fixture)"
                rows.any { it.title.isBlank() } ->
                    fallos += "${def.id}: alguna fila sin title"
                rows.any { !it.pageUrl.startsWith("http") } ->
                    fallos += "${def.id}: pageUrl no absoluta (¿falta resolve:absolute?)"
            }
        }
        assertTrue("Se esperaba validar >=1 canal con fixture", validados >= 1)
        assertTrue("Canales con fixture roto:\n" + fallos.joinToString("\n"), fallos.isEmpty())
    }
}
