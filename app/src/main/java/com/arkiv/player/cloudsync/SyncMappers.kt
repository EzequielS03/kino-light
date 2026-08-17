package com.arkiv.player.cloudsync

import com.arkiv.player.data.db.EpisodeEntity
import com.arkiv.player.data.db.EpisodeFrameEntity
import com.arkiv.player.data.db.ItemEntity
import com.arkiv.player.data.db.LiveFavoriteEntity
import com.arkiv.player.data.db.LiveRecentEntity
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.db.RecomendacionEntity
import com.arkiv.player.data.db.SkipMarkerEntity
import org.json.JSONObject

/**
 * Mapeo record (PocketBase, org.json) <-> entity (Room). Android-side (usa org.json), no puro.
 * Claves naturales por colección: items=identifier, episodes=epId(=EpisodeEntity.id),
 * playback=episodeId, markers=itemId, live_favorites/live_recents=code.
 */

private fun JSONObject.optStringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotEmpty() } else null

private fun JSONObject.optLongOrNull(name: String): Long? =
    if (has(name) && !isNull(name)) optLong(name) else null

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null

// ---- library_items <-> ItemEntity ----

fun itemToFields(entity: ItemEntity, accountId: String): Map<String, Any?> = mapOf(
    "accountId" to accountId,
    "identifier" to entity.identifier,
    "title" to entity.title,
    // Un TOMBSTONE viaja liviano: `torrentData` de un pack puede pesar cientos de miles de
    // caracteres (uno real: 633 112) contra el límite de 200 000 del servidor, que rechazaba la
    // fila y hacía que el borrado no se propagara nunca. Para borrar no hace falta el payload.
    "description" to entity.description.takeUnless { entity.deleted },
    "thumbnailUrl" to entity.thumbnailUrl,
    "addedAt" to entity.addedAt,
    "categoryOverride" to entity.categoryOverride,
    "source" to entity.source,
    "torrentData" to entity.torrentData.takeUnless { entity.deleted },
    "updatedAt" to entity.updatedAt,
    "deleted" to entity.deleted,
)

fun recordToItem(json: JSONObject): ItemEntity = ItemEntity(
    identifier = json.optString("identifier"),
    title = json.optString("title"),
    description = json.optStringOrNull("description"),
    thumbnailUrl = json.optString("thumbnailUrl"),
    addedAt = json.optLong("addedAt"),
    categoryOverride = json.optStringOrNull("categoryOverride"),
    source = json.optStringOrNull("source") ?: "archive",
    torrentData = json.optStringOrNull("torrentData"),
    updatedAt = json.optLong("updatedAt"),
    deleted = json.optBoolean("deleted"),
)

// ---- episodes <-> EpisodeEntity ----

fun episodeToFields(entity: EpisodeEntity, accountId: String): Map<String, Any?> = mapOf(
    "accountId" to accountId,
    "epId" to entity.id,
    "itemId" to entity.itemId,
    "section" to entity.section,
    "displayName" to entity.displayName,
    "orderIndex" to entity.orderIndex,
    "durationSeconds" to entity.durationSeconds,
    "thumbPath" to entity.thumbPath,
    "originalPath" to entity.originalPath,
    "originalFormat" to entity.originalFormat,
    "originalSize" to entity.originalSize,
    "derivativePath" to entity.derivativePath,
    "derivativeFormat" to entity.derivativeFormat,
    "derivativeSize" to entity.derivativeSize,
    "torrentFileIndex" to entity.torrentFileIndex,
    "torrentData" to entity.torrentData,
    "updatedAt" to entity.updatedAt,
    "deleted" to entity.deleted,
)

fun recordToEpisode(json: JSONObject): EpisodeEntity = EpisodeEntity(
    id = json.optString("epId"),
    itemId = json.optString("itemId"),
    section = json.optString("section"),
    displayName = json.optString("displayName"),
    orderIndex = json.optInt("orderIndex"),
    durationSeconds = json.optDouble("durationSeconds"),
    thumbPath = json.optStringOrNull("thumbPath"),
    originalPath = json.optStringOrNull("originalPath"),
    originalFormat = json.optStringOrNull("originalFormat"),
    originalSize = json.optLong("originalSize"),
    derivativePath = json.optStringOrNull("derivativePath"),
    derivativeFormat = json.optStringOrNull("derivativeFormat"),
    derivativeSize = json.optLong("derivativeSize"),
    torrentFileIndex = json.optIntOrNull("torrentFileIndex"),
    torrentData = json.optStringOrNull("torrentData"),
    updatedAt = json.optLong("updatedAt"),
    deleted = json.optBoolean("deleted"),
)

// ---- progress <-> PlaybackEntity ----

fun playbackToFields(entity: PlaybackEntity, accountId: String): Map<String, Any?> = mapOf(
    "accountId" to accountId,
    "episodeId" to entity.episodeId,
    "positionMs" to entity.positionMs,
    "durationMs" to entity.durationMs,
    "watched" to entity.watched,
    "lastPlayedAt" to entity.lastPlayedAt,
    "updatedAt" to entity.updatedAt,
    "deleted" to entity.deleted,
)

fun recordToPlayback(json: JSONObject): PlaybackEntity = PlaybackEntity(
    episodeId = json.optString("episodeId"),
    positionMs = json.optLong("positionMs"),
    durationMs = json.optLong("durationMs"),
    watched = json.optBoolean("watched"),
    lastPlayedAt = json.optLong("lastPlayedAt"),
    updatedAt = json.optLong("updatedAt"),
    deleted = json.optBoolean("deleted"),
)

// ---- markers <-> SkipMarkerEntity ----

fun markerToFields(entity: SkipMarkerEntity, accountId: String): Map<String, Any?> = mapOf(
    "accountId" to accountId,
    "itemId" to entity.itemId,
    "openingStartMs" to entity.openingStartMs,
    "openingEndMs" to entity.openingEndMs,
    "endingStartMs" to entity.endingStartMs,
    "updatedAt" to entity.updatedAt,
    "deleted" to entity.deleted,
)

fun recordToMarker(json: JSONObject): SkipMarkerEntity = SkipMarkerEntity(
    itemId = json.optString("itemId"),
    openingStartMs = json.optLongOrNull("openingStartMs"),
    openingEndMs = json.optLongOrNull("openingEndMs"),
    endingStartMs = json.optLongOrNull("endingStartMs"),
    updatedAt = json.optLong("updatedAt"),
    deleted = json.optBoolean("deleted"),
)

// ---- live_favorites <-> LiveFavoriteEntity ----
// Mismo esquema que markers: LWW por updatedAt + tombstone (deleted).

fun liveFavoriteToFields(entity: LiveFavoriteEntity, accountId: String): Map<String, Any?> = mapOf(
    "accountId" to accountId,
    "code" to entity.code,
    "nombre" to entity.nombre,
    "numero" to entity.numero,
    "logo" to entity.logo,
    "updatedAt" to entity.updatedAt,
    "deleted" to entity.deleted,
)

fun recordToLiveFavorite(json: JSONObject): LiveFavoriteEntity = LiveFavoriteEntity(
    code = json.optString("code"),
    nombre = json.optString("nombre"),
    numero = json.optInt("numero"),
    logo = json.optStringOrNull("logo"),
    updatedAt = json.optLong("updatedAt"),
    deleted = json.optBoolean("deleted"),
)

// ---- live_recents <-> LiveRecentEntity ----
// Sin `deleted`: esta tabla no lleva tombstone (se poda por antigüedad, no se borra a mano) --
// ver el KDoc de LiveRecentEntity en data/db/Entities.kt.

fun liveRecentToFields(entity: LiveRecentEntity, accountId: String): Map<String, Any?> = mapOf(
    "accountId" to accountId,
    "code" to entity.code,
    "nombre" to entity.nombre,
    "vistoAt" to entity.vistoAt,
    "updatedAt" to entity.updatedAt,
)

fun recordToLiveRecent(json: JSONObject): LiveRecentEntity = LiveRecentEntity(
    code = json.optString("code"),
    nombre = json.optString("nombre"),
    vistoAt = json.optLong("vistoAt"),
    updatedAt = json.optLong("updatedAt"),
)

// ---- recomendaciones -> RecomendacionEntity ----
// SOLO LECTURA: la app nunca escribe acá (ver KDoc de RecomendacionEntity), así que no hay
// `recomendacionToFields` -- solo la mitad que hace falta para que el pull/SSE la traiga a Room.
// La clave local es `id` (el de PocketBase), no `orden`: ver el KDoc de RecomendacionEntity.

fun recordToRecomendacion(json: JSONObject): RecomendacionEntity = RecomendacionEntity(
    id = json.optString("id"),
    tmdbId = json.optInt("tmdbId"),
    tipo = json.optString("tipo"),
    titulo = json.optString("titulo"),
    posterUrl = json.optString("posterUrl"),
    porque = json.optString("porque"),
    ref = json.optString("ref"),
    orden = json.optInt("orden"),
    generadoAt = json.optLong("generadoAt"),
    updatedAt = json.optLong("updatedAt"),
    deleted = json.optBoolean("deleted"),
)

// ---- frames <-> EpisodeFrameEntity ----

/**
 * La fila del frame hacia PocketBase. `capturedAt`, `remoteUrl` y `origenRemoto` NO viajan: el
 * primero es diagnóstico local y los otros dos son estado local (de dónde bajar, y de dónde vino la
 * fila), no datos de la fila.
 *
 * El TOMBSTONE manda `img = null` EXPLÍCITO, no omite el campo: en PocketBase omitir un campo
 * significa "no lo toques", así que sin esto el JPEG quedaba en el servidor para siempre aunque el
 * borrado se propagara a todos los dispositivos. Una fila VIVA sí omite `img` a propósito — sus
 * bytes viajan aparte, en el multipart de `CloudSyncManager.subirFrame`, y mandar null acá borraría
 * el archivo bueno en cada push que no adjunte imagen.
 */
fun frameToFields(entity: EpisodeFrameEntity, accountId: String): Map<String, Any?> = buildMap {
    put("accountId", accountId)
    put("episodeId", entity.episodeId)
    put("positionMs", entity.positionMs)
    put("updatedAt", entity.updatedAt)
    put("deleted", entity.deleted)
    if (entity.deleted == 1) put("img", null)
}

/**
 * La fila del frame desde PocketBase, con la URL de su archivo ya armada.
 *
 * `capturedAt` toma el `updatedAt` remoto: el instante real de captura vivía en el otro
 * dispositivo y no viaja, y este campo solo se usa para diagnóstico.
 *
 * `origenRemoto = 1` marca la fila como adoptada: es lo que impide que este dispositivo le re-suba
 * al servidor los mismos bytes que acaba de bajar (ver [EpisodeFrameEntity.origenRemoto]).
 */
fun recordToFrame(json: JSONObject, baseUrl: String): EpisodeFrameEntity {
    val archivo = json.optString("img")
    val url = archivo.takeIf { it.isNotBlank() }?.let {
        "$baseUrl/api/files/${json.optString("collectionId")}/${json.optString("id")}/$it"
    }
    return EpisodeFrameEntity(
        episodeId = json.optString("episodeId"),
        positionMs = json.optLong("positionMs"),
        capturedAt = json.optLong("updatedAt"),
        updatedAt = json.optLong("updatedAt"),
        deleted = json.optInt("deleted"),
        remoteUrl = url,
        origenRemoto = 1,
    )
}
