package com.arkiv.player.cloudsync

import android.util.Log
import com.arkiv.player.data.db.EpisodeEntity
import com.arkiv.player.data.db.EpisodeFrameDao
import com.arkiv.player.data.db.EpisodeFrameEntity
import com.arkiv.player.data.db.ItemDao
import com.arkiv.player.data.db.PlaybackDao
import com.arkiv.player.data.db.SkipMarkerDao
import com.arkiv.player.miniaturas.AlmacenDeFrames
import com.arkiv.player.miniaturas.DestructorDeFrames
import com.arkiv.player.pocketbase.DeviceAuthManager
import com.arkiv.player.pocketbase.PocketBaseConfig
import com.arkiv.player.pocketbase.PocketBaseRealtime
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val TAG = "ArkivPB"

private const val COL_ITEMS = "library_items"
private const val COL_EPISODES = "episodes"
private const val COL_PROGRESS = "progress"
private const val COL_MARKERS = "markers"
private const val COL_FRAMES = "episode_frames"

/** Cuánto retrocede el cursor de pull al arrancar, para rescatar lo que se subió tarde. */
private const val RETROCESO_MS = 7L * 24 * 60 * 60 * 1000

/**
 * Orquesta la sincronización en la nube: empuja cambios locales (push), reconcilia el histórico
 * remoto al arrancar (reconcile) y aplica eventos en vivo (realtime SSE). Resolución de conflictos
 * vía LWW (`updatedAt`), con soft-delete (tombstones) propagados como cualquier otro campo.
 *
 * Offline-first: ninguna falla de red o de sesión debe tumbar el proceso; todo se reintenta en el
 * siguiente tick del loop de push. `reconcileAll`/`subscribeAll` corren dentro de un loop de
 * reintento infinito en [start]: si `deviceAuth.session` aún no está listo, `reconcileAll` no hace
 * nada en ese ciclo y se reintenta 5s después; si `subscribeAll` (SSE) corta la conexión o lanza,
 * se reconecta reconciliando primero (para no perder eventos ocurridos mientras estuvo caído).
 */
class CloudSyncManager(
    private val itemDao: ItemDao,
    private val playbackDao: PlaybackDao,
    private val skipMarkerDao: SkipMarkerDao,
    private val episodeFrameDao: EpisodeFrameDao,
    /** Dónde viven los JPEG en disco: los lee [pushFrames] para adjuntarlos a la fila que sube. */
    private val almacenDeFrames: AlmacenDeFrames,
    private val pbSync: PbSyncClient,
    private val realtime: PocketBaseRealtime,
    private val deviceAuth: DeviceAuthManager,
    private val cursors: SyncCursors,
    private val scope: CoroutineScope,
    private val quarantine: SyncQuarantine,
    /**
     * El MISMO objeto que recibe `ArkivRepository` (y `LibraryWiper`): se instancia una sola vez en
     * `AppGraph` y se pasa por constructor a los tres. El progreso sincroniza HOY —no
     * es la fase 2 de frames—, así que si un capítulo llega visto desde otro dispositivo (p. ej.
     * se vio en el TV) el frame local tiene que morir acá también, ver [mergePlayback].
     */
    private val destructorDeFrames: DestructorDeFrames,
) {
    fun start() {
        // Reparación una sola vez tras actualizar: los cursores guardados por la versión anterior
        // pueden haber pasado por encima de filas que nunca llegaron al servidor (borrados que se
        // perdieron). Volverlos a cero fuerza un sync completo; es idempotente y LWW lo resuelve.
        if (cursors.necesitaReparacion()) {
            Log.w(TAG, "cloudsync: reparando cursores (sync completo por única vez)")
            cursors.resetAll(listOf(COL_ITEMS, COL_EPISODES, COL_PROGRESS, COL_MARKERS))
            cursors.marcarReparado()
        }
        scope.launch {
            var primerCiclo = true
            while (true) {
                try {
                    reconcileAll(conRetroceso = primerCiclo)
                    primerCiclo = false
                    subscribeAll() // colecta el SSE indefinidamente; si lanza, reintentamos
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "cloudsync reconcile/subscribe falló, reintenta: ${e.message}")
                    delay(5000)
                }
            }
        }
        scope.launch {
            while (true) {
                try {
                    pushAll()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "pushAll falló (se reintenta en 5s): ${e.message}", e)
                }
                delay(5000)
            }
        }
    }

    /**
     * Sync manual una vez (para el botón "Sincronizar"): empuja lo local y baja lo remoto.
     * Best-effort: cada mitad corre en su propio runCatching para que un fallo en una no tumbe
     * la otra (p. ej. sin sesión todavía, o un error transitorio de red).
     */
    suspend fun syncNow() {
        // REPARACIÓN: el botón manual reinicia los cursores de push y vuelve a empujar TODO.
        // Hace falta porque el cursor pudo haber pasado por encima de filas que nunca llegaron al
        // servidor (bug corregido en PushFrontier, pero los cursores ya dañados siguen ahí): sin
        // esto, esos cambios quedan enterrados para siempre. El upsert es idempotente y el merge es
        // LWW, así que re-empujar todo es seguro — solo cuesta ancho de banda, y es un gesto manual.
        cursors.resetAll(listOf(COL_ITEMS, COL_EPISODES, COL_PROGRESS, COL_MARKERS, COL_FRAMES))
        runCatching { pushAll() }
        runCatching { reconcileAll() }
    }

    // ---- push: local -> PocketBase ----

    private suspend fun pushAll() {
        val acct = deviceAuth.session.value?.accountId ?: return
        pushRows(COL_ITEMS, itemDao.getItemsSince(cursors.lastPushed(COL_ITEMS)),
            { it.identifier }, { itemToFields(it, acct) }, { it.updatedAt })
        pushRows(COL_EPISODES, itemDao.getEpisodesSince(cursors.lastPushed(COL_EPISODES)),
            { it.id }, { episodeToFields(it, acct) }, { it.updatedAt })
        pushRows(COL_PROGRESS, playbackDao.getPlaybackSince(cursors.lastPushed(COL_PROGRESS)),
            { it.episodeId }, { playbackToFields(it, acct) }, { it.updatedAt })
        pushRows(COL_MARKERS, skipMarkerDao.getMarkersSince(cursors.lastPushed(COL_MARKERS)),
            { it.itemId }, { markerToFields(it, acct) }, { it.updatedAt })
        pushFrames(acct)
    }

    /**
     * Empuja las filas de una colección de forma RESILIENTE POR FILA: si una fila falla (p. ej.
     * validación de campo en el servidor), se registra y se SALTA en vez de bloquear toda la
     * colección. El cursor avanza al máximo `updatedAt` de TODAS las filas procesadas (para
     * garantizar progreso: una fila permanentemente inválida no atasca el sync para siempre).
     * Las cancelaciones se re-lanzan (no se tragan). El campo natural-key va por el nombre PB:
     * items=identifier, episodes=epId (=id de la entidad), progress=episodeId, markers=itemId.
     * Frames no pasa por acá: tiene su propio camino, ver [pushFrames].
     */
    private suspend fun <T> pushRows(
        col: String,
        rows: List<T>,
        key: (T) -> String,
        fields: (T) -> Map<String, Any?>,
        updatedAt: (T) -> Long,
    ) {
        if (rows.isEmpty()) return
        val keyField = when (col) {
            COL_ITEMS -> "identifier"; COL_EPISODES -> "epId"; COL_PROGRESS -> "episodeId"
            else -> "itemId"
        }
        // En orden cronológico: la marca de agua se corta en la fila sin resolver más vieja.
        val outcomes = rows.sortedBy { updatedAt(it) }.map { row ->
            val k = key(row)
            val resuelta = if (quarantine.enCuarentena(col, k)) {
                true // ya se rindió antes; no la reintentamos ni dejamos que atasque la colección
            } else {
                try {
                    pbSync.upsert(col, keyField, k, fields(row))
                    quarantine.limpiar(col, k)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val n = quarantine.registrarFallo(col, k)
                    if (n >= SyncQuarantine.MAX_INTENTOS) {
                        Log.e(TAG, "cloudsync: fila $col '$k' EN CUARENTENA tras $n intentos, " +
                            "el cursor la pasa de largo: ${e.message}")
                    } else {
                        Log.w(TAG, "cloudsync: fila $col '$k' falló (intento $n), se reintenta: ${e.message}")
                    }
                    quarantine.enCuarentena(col, k)
                }
            }
            RowOutcome(updatedAt(row), resuelta)
        }
        cursors.setLastPushed(col, PushFrontier.advance(cursors.lastPushed(col), outcomes))
    }

    /**
     * Empuja los frames. Mismo esquema de resiliencia que [pushRows] (cuarentena por fila,
     * cancelación relanzada, cursor que avanza solo sobre lo resuelto vía [PushFrontier]) pero con
     * camino propio: a diferencia del resto de las colecciones, cada fila puede llevar además los
     * bytes del JPEG, y el `fields` genérico de [pushRows] no sabe de archivos.
     */
    private suspend fun pushFrames(acct: String) {
        val rows = episodeFrameDao.getFramesSince(cursors.lastPushed(COL_FRAMES))
        if (rows.isEmpty()) return
        val outcomes = rows.sortedBy { it.updatedAt }.map { row ->
            val k = row.episodeId
            val resuelta = if (quarantine.enCuarentena(COL_FRAMES, k)) {
                true // ya se rindió antes; no la reintentamos ni dejamos que atasque la colección
            } else {
                try {
                    subirFrame(row, acct)
                    quarantine.limpiar(COL_FRAMES, k)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val n = quarantine.registrarFallo(COL_FRAMES, k)
                    if (n >= SyncQuarantine.MAX_INTENTOS) {
                        Log.e(TAG, "cloudsync: fila $COL_FRAMES '$k' EN CUARENTENA tras $n intentos, " +
                            "el cursor la pasa de largo: ${e.message}")
                    } else {
                        Log.w(TAG, "cloudsync: fila $COL_FRAMES '$k' falló (intento $n), se reintenta: ${e.message}")
                    }
                    quarantine.enCuarentena(COL_FRAMES, k)
                }
            }
            RowOutcome(row.updatedAt, resuelta)
        }
        cursors.setLastPushed(COL_FRAMES, PushFrontier.advance(cursors.lastPushed(COL_FRAMES), outcomes))
    }

    /**
     * Sube una fila de frame. Tres casos suben SOLO la fila, igual que cualquier otra colección:
     * un tombstone (`deleted == 1`, que además manda `img = null` para que el servidor suelte el
     * archivo, ver `frameToFields`), una fila cuyo archivo ya no está en disco, y una fila ADOPTADA
     * de otro dispositivo (`origenRemoto == 1`). Si el archivo existe y la fila nació acá, sus bytes
     * viajan junto con ella en un único request multipart.
     *
     * Lo de `origenRemoto` no es una optimización: re-subir lo que se acaba de bajar cambiaba el
     * nombre del archivo en el servidor SIN cambiar `updatedAt`, con lo que un tercer dispositivo se
     * quedaba con un `remoteUrl` que da 404 para siempre, y si el eco llegaba después de una captura
     * nueva del original hacía retroceder el registro al frame viejo. Ver
     * [com.arkiv.player.data.db.EpisodeFrameEntity.origenRemoto].
     */
    private suspend fun subirFrame(row: EpisodeFrameEntity, acct: String) {
        val local = row.deleted == 0 && row.origenRemoto == 0
        val ruta = if (local) almacenDeFrames.rutaSiExiste(row.episodeId) else null
        if (ruta == null) {
            pbSync.upsert(COL_FRAMES, "episodeId", row.episodeId, frameToFields(row, acct))
        } else {
            pbSync.upsertConArchivo(
                COL_FRAMES, "episodeId", row.episodeId, frameToFields(row, acct),
                campoArchivo = "img", nombre = almacenDeFrames.archivoDe(row.episodeId).name,
                bytes = File(ruta).readBytes(),
            )
        }
    }

    // ---- reconcile: pull histórico remoto desde el último cursor de pull ----

    private suspend fun reconcileAll(conRetroceso: Boolean = false) {
        // Requiere sesión; si aún no hay una, se salta este ciclo (no hay retry propio aquí más
        // allá de los eventos realtime que lleguen luego).
        deviceAuth.session.value ?: return

        for (col in listOf(COL_ITEMS, COL_EPISODES, COL_PROGRESS, COL_MARKERS, COL_FRAMES)) {
            val cursor = cursors.lastPulled(col)
            // Ventana de retroceso al arrancar: el cursor usa el reloj del CLIENTE, así que un
            // cambio hecho sin conexión se sube más tarde con su fecha original — nace por debajo
            // del cursor y no se vería nunca (pasó: una serie borrada el 07-24 seguía en el TV
            // días después). Volver a mirar los últimos días lo rescata. Solo en el primer ciclo:
            // en cada reconexión del SSE sería traer lo mismo una y otra vez.
            val desde = if (conRetroceso) maxOf(0L, cursor - RETROCESO_MS) else cursor
            val remotes = pbSync.pullSince(col, desde)
            remotes.forEach { mergeRecord(col, it) }
            remotes.maxOfOrNull { it.optLong("updatedAt") }
                ?.let { cursors.setLastPulled(col, maxOf(cursor, it)) }
        }
    }

    // ---- realtime: aplica eventos SSE apenas llegan ----

    private suspend fun subscribeAll() {
        realtime.subscribe(listOf(COL_ITEMS, COL_EPISODES, COL_PROGRESS, COL_MARKERS, COL_FRAMES)).collect { ev ->
            mergeRecord(ev.topic, ev.record)
        }
    }

    // ---- merge LWW + tombstone ----

    /** Aplica (si gana) un registro remoto a Room y devuelve si se aplicó. */
    private suspend fun mergeRecord(col: String, json: JSONObject) {
        val remoteUpdatedAt = json.optLong("updatedAt")
        when (col) {
            COL_ITEMS -> mergeItem(json, remoteUpdatedAt)
            COL_EPISODES -> mergeEpisode(json, remoteUpdatedAt)
            COL_PROGRESS -> mergePlayback(json, remoteUpdatedAt)
            COL_MARKERS -> mergeMarker(json, remoteUpdatedAt)
            COL_FRAMES -> mergeFrame(json, remoteUpdatedAt)
            else -> false
        }
        // OJO: NO bumpear cursors.lastPushed acá. El cursor de push es por colección (no por fila)
        // y getXSince(cursor) filtra updatedAt > cursor para TODA la colección: si avanzáramos el
        // cursor a remoteUpdatedAt para "evitar el eco", podríamos saltarnos permanentemente otras
        // filas locales editadas pero aún no empujadas (pérdida de datos silenciosa). El eco de
        // volver a empujar esta misma fila es inofensivo y acotado: se reenvía con el mismo
        // updatedAt, y en el otro dispositivo LwwMerge.pickWinner(local, remote) da false para
        // timestamps iguales → no-op. Se resuelve en una vuelta extra, sin loop.
    }

    private suspend fun mergeItem(json: JSONObject, remoteUpdatedAt: Long): Boolean {
        val remote = recordToItem(json)
        val local = itemDao.getItem(remote.identifier)
        if (!LwwMerge.pickWinner(local?.updatedAt ?: 0, remoteUpdatedAt)) return false
        itemDao.upsertItem(remote)
        return true
    }

    private suspend fun mergeEpisode(json: JSONObject, remoteUpdatedAt: Long): Boolean {
        val remote: EpisodeEntity = recordToEpisode(json)
        val local = itemDao.getEpisode(remote.id)
        if (!LwwMerge.pickWinner(local?.updatedAt ?: 0, remoteUpdatedAt)) return false
        itemDao.upsertEpisodes(listOf(remote))
        return true
    }

    private suspend fun mergePlayback(json: JSONObject, remoteUpdatedAt: Long): Boolean {
        val remote = recordToPlayback(json)
        val local = playbackDao.get(remote.episodeId)
        if (!LwwMerge.pickWinner(local?.updatedAt ?: 0, remoteUpdatedAt)) return false
        playbackDao.upsert(remote)
        // El remoto ganó el merge: si trae el capítulo visto, el frame de ESTE dispositivo tiene
        // que morir también (ver el doc del constructor). Si trae watched = false, no se toca
        // nada: el capítulo vuelve a estar en curso en todas partes.
        if (remote.watched) destructorDeFrames.destruir(remote.episodeId)
        return true
    }

    private suspend fun mergeMarker(json: JSONObject, remoteUpdatedAt: Long): Boolean {
        val remote = recordToMarker(json)
        val local = skipMarkerDao.get(remote.itemId)
        if (!LwwMerge.pickWinner(local?.updatedAt ?: 0, remoteUpdatedAt)) return false
        skipMarkerDao.upsert(remote)
        return true
    }

    /**
     * Aplica una fila de frame remota si gana el LWW.
     *
     * Cuando el remoto llega con `deleted = 1` no alcanza con guardar la fila: hay que destruir el
     * JPEG local, o el archivo queda ocupando disco para siempre y —peor— se seguiría pintando, porque
     * la ruta la resuelve el disco y no la fila (decisión de la fase 1).
     *
     * La comparación LWW usa [EpisodeFrameDao.getIncluyendoBorradas] y NO [EpisodeFrameDao.get] a
     * propósito: desde que `DestructorDeFrames.destruir` deja tombstone (fase 2), este dispositivo
     * puede tener un frame borrado localmente (`deleted = 1`, `updatedAt` reciente). Si acá
     * usáramos `get` (que filtra `deleted = 0`), ese tombstone se vería como "no hay fila", el
     * remoto ganaría siempre el LWW así fuera más viejo, y el frame borrado resucitaría.
     */
    private suspend fun mergeFrame(json: JSONObject, remoteUpdatedAt: Long): Boolean {
        val episodeId = json.optString("episodeId")
        if (episodeId.isBlank()) return false
        val local = episodeFrameDao.getIncluyendoBorradas(episodeId)
        if (!LwwMerge.pickWinner(local?.updatedAt ?: 0L, remoteUpdatedAt)) return false
        episodeFrameDao.upsert(recordToFrame(json, PocketBaseConfig.BASE_URL))
        // OJO: NO destructorDeFrames.destruir(episodeId) acá. El upsert de arriba ya dejó la fila
        // bien sellada con el updatedAt REMOTO (el que ganó el LWW); destruir() la volvería a
        // pisar con el reloj LOCAL, inflando el timestamp del borrado por encima del real —con
        // riesgo de perder, contra ese timestamp inflado, una actualización legítima de un tercer
        // dispositivo que todavía no llegó— y generando un push de eco extra. Lo único que falta
        // acá es lo que ese upsert no hace: borrar el JPEG viejo del disco.
        if (json.optInt("deleted") == 1) destructorDeFrames.borrarArchivo(episodeId)
        return true
    }
}
