package com.arkiv.player.pocketbase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class PocketBaseException(val code: Int, message: String) : Exception(message)

/** Tamaño de página al listar registros (PocketBase admite hasta 500). */
private const val PER_PAGE = 200

data class AuthResult(val token: String, val recordId: String)

data class AuthRecord(val token: String, val recordId: String, val record: org.json.JSONObject)

class PocketBaseClient(
    private val baseUrl: String = PocketBaseConfig.BASE_URL,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val jsonType = "application/json".toMediaType()

    suspend fun createRecord(collection: String, fields: Map<String, Any?>, token: String? = null): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject(fields).toString().toRequestBody(jsonType)
            val req = Request.Builder()
                .url("$baseUrl/api/collections/$collection/records")
                .apply { if (token != null) header("Authorization", token) }
                .post(body)
                .build()
            execute(req).getString("id")
        }

    suspend fun authWithPassword(collection: String, identity: String, password: String): AuthResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject(mapOf("identity" to identity, "password" to password))
                .toString().toRequestBody(jsonType)
            val req = Request.Builder()
                .url("$baseUrl/api/collections/$collection/auth-with-password")
                .post(body)
                .build()
            val json = execute(req)
            AuthResult(json.getString("token"), json.getJSONObject("record").getString("id"))
        }

    suspend fun authWithPasswordRecord(collection: String, identity: String, password: String): AuthRecord =
        withContext(Dispatchers.IO) {
            val body = JSONObject(mapOf("identity" to identity, "password" to password))
                .toString().toRequestBody(jsonType)
            val req = Request.Builder()
                .url("$baseUrl/api/collections/$collection/auth-with-password")
                .post(body)
                .build()
            val json = execute(req)
            val record = json.getJSONObject("record")
            AuthRecord(json.getString("token"), record.getString("id"), record)
        }

    suspend fun authRefresh(collection: String, token: String): AuthResult =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$baseUrl/api/collections/$collection/auth-refresh")
                .header("Authorization", token)
                .post(ByteArray(0).toRequestBody(null))
                .build()
            val json = execute(req)
            AuthResult(json.getString("token"), json.getJSONObject("record").getString("id"))
        }

    suspend fun updateRecord(collection: String, id: String, fields: Map<String, Any?>, token: String) {
        withContext(Dispatchers.IO) {
            val body = JSONObject(fields).toString().toRequestBody(jsonType)
            val req = Request.Builder()
                .url("$baseUrl/api/collections/$collection/records/$id")
                .header("Authorization", token)
                .patch(body)
                .build()
            execute(req)
        }
    }

    /** Igual que [createRecord] pero adjuntando un archivo. Ver [PocketBaseMultipart]. */
    suspend fun createRecordConArchivo(
        collection: String,
        fields: Map<String, Any?>,
        campoArchivo: String,
        nombre: String,
        bytes: ByteArray,
        token: String,
    ): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/api/collections/$collection/records")
            .header("Authorization", token)
            .post(PocketBaseMultipart.build(fields, campoArchivo, nombre, bytes))
            .build()
        execute(req).getString("id")
    }

    /** Igual que [updateRecord] pero adjuntando un archivo. */
    suspend fun updateRecordConArchivo(
        collection: String,
        id: String,
        fields: Map<String, Any?>,
        campoArchivo: String,
        nombre: String,
        bytes: ByteArray,
        token: String,
    ) {
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$baseUrl/api/collections/$collection/records/$id")
                .header("Authorization", token)
                .patch(PocketBaseMultipart.build(fields, campoArchivo, nombre, bytes))
                .build()
            execute(req)
        }
    }

    /**
     * Lista TODOS los registros que matchean el filtro, recorriendo las páginas.
     *
     * Antes pedía una sola página de 200 y se quedaba con eso: con ~1000 episodios, cualquier
     * consulta grande se truncaba EN SILENCIO (el resto no existía para el sync). [sort] fija un
     * orden estable — sin él, paginar puede repetir o saltarse filas entre páginas.
     *
     * El default es `id` porque es el único campo que TODAS las colecciones tienen: las de esta app
     * se crearon sin los campos autodate `created`/`updated`, así que ordenar por ellos daría error.
     */
    suspend fun listRecords(
        collection: String,
        filter: String,
        token: String,
        sort: String = "id",
    ): List<JSONObject> =
        withContext(Dispatchers.IO) {
            val acumulado = mutableListOf<JSONObject>()
            var page = 1
            while (true) {
                val url = "$baseUrl/api/collections/$collection/records".toHttpUrl().newBuilder()
                    .addQueryParameter("filter", filter)
                    .addQueryParameter("perPage", PER_PAGE.toString())
                    .addQueryParameter("page", page.toString())
                    .addQueryParameter("sort", sort)
                    .build()
                val req = Request.Builder().url(url).header("Authorization", token).get().build()
                val resp = execute(req)
                val items = resp.getJSONArray("items")
                for (i in 0 until items.length()) acumulado += items.getJSONObject(i)

                val totalPages = resp.optInt("totalPages", 1)
                if (page >= totalPages || items.length() == 0) break
                page++
            }
            acumulado
        }

    /**
     * File-token de vida corta para leer un archivo `protected` (ver `BajadorDeFrames`, el único
     * consumidor hoy: el campo `img` de `episode_frames` se creó protegido porque son escenas de
     * lo que mira el usuario). El header `Authorization` de la sesión NO alcanza para pedir el
     * archivo directamente -PocketBase exige este token aparte, como query param `?token=` de la
     * URL del archivo (ver [downloadFile]).
     */
    suspend fun fileToken(token: String): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$baseUrl/api/files/token")
                .header("Authorization", token)
                .post(ByteArray(0).toRequestBody(null))
                .build()
            execute(req).getString("token")
        }

    /**
     * Bytes crudos de un archivo de PocketBase. No pasa por [execute] porque la respuesta no es
     * JSON. El parámetro `fileToken` es el que devuelve [fileToken] (el método de arriba).
     */
    suspend fun downloadFile(url: String, fileToken: String): ByteArray =
        withContext(Dispatchers.IO) {
            val target = url.toHttpUrl().newBuilder().addQueryParameter("token", fileToken).build()
            val req = Request.Builder().url(target).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw PocketBaseException(resp.code, "GET $url -> ${resp.code}")
                resp.body?.bytes() ?: throw PocketBaseException(resp.code, "cuerpo vacío")
            }
        }

    suspend fun deleteRecord(collection: String, id: String, token: String) {
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$baseUrl/api/collections/$collection/records/$id")
                .header("Authorization", token)
                .delete()
                .build()
            execute(req, allowEmpty = true)
        }
    }

    private fun execute(req: Request, allowEmpty: Boolean = false): JSONObject {
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(raw).optString("message") }.getOrNull()
                throw PocketBaseException(resp.code, msg?.ifBlank { raw } ?: raw)
            }
            if (raw.isBlank()) {
                if (allowEmpty) return JSONObject()
                throw PocketBaseException(resp.code, "respuesta vacía")
            }
            return JSONObject(raw)
        }
    }
}
