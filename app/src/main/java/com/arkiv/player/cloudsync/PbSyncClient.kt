package com.arkiv.player.cloudsync

import com.arkiv.player.pocketbase.DeviceAuthManager
import com.arkiv.player.pocketbase.PocketBaseClient
import org.json.JSONObject

/**
 * Cliente de sincronización sobre [PocketBaseClient]: upsert por clave natural (identifier,
 * epId, episodeId, itemId según la colección) y pull incremental por `updatedAt`.
 *
 * No atrapa excepciones de forma silenciosa: si algo falla (red, PocketBase, sesión ausente),
 * se relanza para que el llamador (CloudSyncManager) reintente más tarde. Nunca se traga
 * CancellationException.
 */
class PbSyncClient(
    private val client: PocketBaseClient,
    private val deviceAuth: DeviceAuthManager,
) {
    /** Escapa comillas simples en valores de filtro PocketBase (naturalKey es id/identifier, pero
     *  se escapa a la defensiva). */
    private fun escapeFilterValue(value: String): String = value.replace("'", "''")

    /**
     * Busca por `accountId='<acct>' && <naturalKeyField>='<key>'`; si existe, actualiza; si no,
     * crea. [fields] ya incluye accountId (lo arma el llamador vía SyncMappers).
     */
    suspend fun upsert(
        collection: String,
        naturalKeyField: String,
        naturalKey: String,
        fields: Map<String, Any?>,
    ) {
        val session = deviceAuth.session.value
            ?: throw IllegalStateException("no hay sesión de dispositivo activa (offline)")

        val filter = "accountId='${escapeFilterValue(session.accountId)}' && " +
            "$naturalKeyField='${escapeFilterValue(naturalKey)}'"
        val existing = client.listRecords(collection, filter, session.token)

        if (existing.isNotEmpty()) {
            client.updateRecord(collection, existing.first().getString("id"), fields, session.token)
        } else {
            client.createRecord(collection, fields, session.token)
        }
    }

    /**
     * Filas de MI cuenta con `updatedAt > cursor`, ordenadas por updatedAt ascendente.
     *
     * El orden de paginación es `updatedAt,id`: `updatedAt` solo no alcanza porque se repite entre
     * filas (un borrado en lote sella varias con el mismo milisegundo) y un orden ambiguo hace que
     * al paginar se salten o se repitan registros. `id` desempata.
     *
     * OJO: el cursor es el reloj del CLIENTE, así que un cambio hecho sin conexión se sube después
     * con su fecha original — o sea, nace por debajo del cursor y no se vería nunca. Por eso el
     * llamador aplica una ventana de retroceso al arrancar (ver CloudSyncManager). Lo ideal sería
     * paginar por el reloj del servidor, pero estas colecciones se crearon sin los campos autodate
     * `created`/`updated` de PocketBase; agregarlos habilitaría esa mejora.
     */
    suspend fun pullSince(collection: String, cursor: Long): List<JSONObject> {
        val session = deviceAuth.session.value
            ?: throw IllegalStateException("no hay sesión de dispositivo activa (offline)")

        val filter = "accountId='${escapeFilterValue(session.accountId)}' && updatedAt > $cursor"
        val records = client.listRecords(collection, filter, session.token, sort = "updatedAt,id")
        return records.sortedBy { it.optLong("updatedAt") }
    }
}
