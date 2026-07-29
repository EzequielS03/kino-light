package com.arkiv.player.data.catalog.web

import org.json.JSONObject

/** Reglas de parsing del listado de una web. Extracción de filas por rowSelector (CSS) O rowRegex. */
data class WebParserRules(
    val rowSelector: String?,
    val rowRegex: String?,
    val rowRegexFields: List<String>,   // nombres de campo por grupo capturado, en orden
    val title: FieldRule?,
    val pageUrl: FieldRule?,
    val poster: FieldRule?,
    val year: FieldRule?,
    val quality: FieldRule?,
    val kindHint: FieldRule?,
)

/**
 * Reglas para enumerar los episodios de la PÁGINA DE UNA SERIE (para el "pack de serie").
 * Mismo estilo que [WebParserRules.rowRegex]: un regex sobre los anchors de episodio y los nombres
 * de campo por grupo capturado. Campos reconocidos: "pageUrl", "season", "episode", "title".
 */
data class WebEpisodeRules(
    val regex: String,
    val fields: List<String>,
)

/**
 * Reglas para canales con API JSON (estilo el read_api de Alfa: p.ej. lamovie /wp-api/v1/...).
 * En vez de selectores CSS, se navegan rutas del JSON con notación de puntos ("data.posts").
 * poster/year suelen ir vacíos (los rellena TMDB vía WebTmdbMatcher, igual que Alfa).
 */
data class WebApiRules(
    val browse: Map<String, String>,   // kind -> pathTemplate con {page}
    val search: String?,               // pathTemplate con {query}
    val listPath: String,              // ruta al array de resultados, ej "data.posts"
    val title: String,                 // ruta al título dentro de cada item, ej "original_title"
    val pageUrl: String,               // plantilla con {ruta} sustituida por campos del item, ej "/wp-api/v1/player?postId={_id}"
    val poster: String?,               // ruta opcional
    val year: String?,                 // ruta opcional
)

/** Definición declarativa de una web de streaming (espejo de ProviderDefinition, salida = páginas). */
data class WebSourceDefinition(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val priority: Int,
    val baseUrl: String,
    val hostAlt: List<String>,
    val charset: String,
    val needsCloudflare: Boolean,
    val languageTokens: List<String>,
    val browse: Map<String, String>,   // kind -> pathTemplate con {page}
    val search: String?,               // pathTemplate con {query}
    val keywords: Map<String, String>, // kind -> plantilla de query
    val parser: WebParserRules,
    val api: WebApiRules? = null,       // si != null, el canal usa API JSON (WebJsonBackend) en vez de HTML
    val episodes: WebEpisodeRules? = null, // si != null, la web puede enumerar episodios de una serie
) {
    companion object {
        /** Parsea y VALIDA. Devuelve null (sin lanzar) si la definición es inválida. */
        fun fromJson(o: JSONObject): WebSourceDefinition? = runCatching {
            val id = o.optString("id").ifBlank { return null }
            val baseUrl = o.optString("baseUrl").ifBlank { return null }
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) return null

            // Modo API JSON (estilo Alfa read_api): si hay bloque "api" válido, el parser HTML es opcional.
            val api = parseApi(o.optJSONObject("api"))

            val pj = o.optJSONObject("parser")
            val rowSelector = pj?.optString("rowSelector")?.ifBlank { null }
            val rowRegex = pj?.optString("rowRegex")?.ifBlank { null }
            // Debe tener O API O un parser HTML con forma de fila; si no, es inválida.
            if (api == null && rowSelector == null && rowRegex == null) return null
            val fieldsJson = pj?.optJSONArray("rowRegexFields")
            val rowRegexFields = if (fieldsJson == null) emptyList()
                else (0 until fieldsJson.length()).map { fieldsJson.getString(it) }

            val browseJson = o.optJSONObject("browse")
            val browse = browseJson?.keys()?.asSequence()?.associateWith { browseJson.getString(it) } ?: emptyMap()
            val keywordsJson = o.optJSONObject("keywords")
            val keywords = keywordsJson?.keys()?.asSequence()?.associateWith { keywordsJson.getString(it) } ?: emptyMap()

            val altJson = o.optJSONArray("hostAlt")
            val hostAlt = if (altJson == null) emptyList()
                else (0 until altJson.length()).map { altJson.getString(it).trimEnd('/') }
            val tokensJson = o.optJSONArray("languageTokens")
            val tokens = if (tokensJson == null) emptyList()
                else (0 until tokensJson.length()).map { tokensJson.getString(it) }

            val episodes = parseEpisodes(o.optJSONObject("episodes"))

            WebSourceDefinition(
                id = id,
                name = o.optString("name").ifBlank { id },
                enabled = o.optBoolean("enabled", true),
                priority = o.optInt("priority", 50),
                baseUrl = baseUrl.trimEnd('/'),
                hostAlt = hostAlt,
                charset = o.optString("charset", "utf-8"),
                needsCloudflare = o.optBoolean("needsCloudflare", false),
                languageTokens = tokens,
                browse = browse,
                search = o.optString("search").ifBlank { null },
                keywords = keywords,
                parser = WebParserRules(
                    rowSelector = rowSelector,
                    rowRegex = rowRegex,
                    rowRegexFields = rowRegexFields,
                    title = FieldRule.fromJson(pj?.optJSONObject("title")),
                    pageUrl = FieldRule.fromJson(pj?.optJSONObject("pageUrl")),
                    poster = FieldRule.fromJson(pj?.optJSONObject("poster")),
                    year = FieldRule.fromJson(pj?.optJSONObject("year")),
                    quality = FieldRule.fromJson(pj?.optJSONObject("quality")),
                    kindHint = FieldRule.fromJson(pj?.optJSONObject("kindHint")),
                ),
                api = api,
                episodes = episodes,
            )
        }.getOrNull()

        /** Parsea el bloque "episodes" (enumeración de episodios de una serie). null si ausente/ inválido. */
        private fun parseEpisodes(e: JSONObject?): WebEpisodeRules? {
            if (e == null) return null
            val regex = e.optString("regex").ifBlank { return null }
            val fieldsJson = e.optJSONArray("fields") ?: return null
            val fields = (0 until fieldsJson.length()).map { fieldsJson.getString(it) }
            if (fields.isEmpty()) return null
            return WebEpisodeRules(regex = regex, fields = fields)
        }

        /** Parsea el bloque "api" (modo JSON). null si ausente o inválido. */
        private fun parseApi(a: JSONObject?): WebApiRules? {
            if (a == null) return null
            val listPath = a.optString("listPath").ifBlank { return null }
            val title = a.optString("title").ifBlank { return null }
            val pageUrl = a.optString("pageUrl").ifBlank { return null }
            val bj = a.optJSONObject("browse")
            val browse = bj?.keys()?.asSequence()?.associateWith { bj.getString(it) } ?: emptyMap()
            return WebApiRules(
                browse = browse,
                search = a.optString("search").ifBlank { null },
                listPath = listPath,
                title = title,
                pageUrl = pageUrl,
                poster = a.optString("poster").ifBlank { null },
                year = a.optString("year").ifBlank { null },
            )
        }
    }
}
