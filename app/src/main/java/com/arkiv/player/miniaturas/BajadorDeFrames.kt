package com.arkiv.player.miniaturas

import android.util.Log
import com.arkiv.player.data.db.EpisodeFrameDao
import com.arkiv.player.pocketbase.DeviceAuthManager
import com.arkiv.player.pocketbase.PocketBaseClient
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/**
 * Baja el JPEG de los frames que llegaron por sync desde otro dispositivo: gemela de
 * [FrameCapturer], pero del lado de la lectura. `CloudSyncManager.mergeFrame` ya dejó la fila en
 * Room con `remoteUrl` apuntando al archivo remoto (ver `EpisodeFrameDao.pendientesDeBajar`);
 * a esta clase solo le falta traer los bytes y publicarlos con [AlmacenDeFrames.guardar].
 *
 * PEREZOSA A PROPÓSITO: baja los capítulos que se están por PINTAR, no la cola entera de la cuenta.
 * Quien la llama (`ArkivRepository`) le pasa los `episodeId` de las tarjetas que la pantalla está
 * armando en ese momento. Sin ese filtro, abrir el home en un aparato desincronizado disparaba ~97
 * descargas para pintar 6.
 *
 * DOS PASOS para bajar el archivo, no uno solo -y esto no es un detalle, es LA razón de ser de
 * esta clase-: el campo `img` de la colección `episode_frames` se creó con `protected = true`
 * (ver `docs/pocketbase/1786500000_created_episode_frames.js`) porque son escenas de lo que mira
 * el usuario, y PocketBase NO sirve un archivo protegido con el header `Authorization` de la
 * sesión del dispositivo. Hace falta:
 *   1. `POST /api/files/token` con la sesión, que devuelve un file-token de vida corta.
 *   2. `GET remoteUrl?token=<ese file-token>`, recién ahí vienen los bytes.
 * Si algún día las miniaturas remotas dejan de cargar (se ven solo las locales, o el respaldo de
 * TMDB en cada capítulo de otro dispositivo), ES ACÁ donde hay que mirar primero: cada fallo se
 * loguea con el tag `ArkivPB`.
 *
 * El file-token se pide DE NUEVO en cada pasada (nunca se cachea entre pasadas): es de vida
 * corta, y guardar uno viejo para la próxima vuelta terminaría en 403 más seguido de lo necesario
 * en vez de simplemente pedir uno fresco.
 *
 * Best-effort de punta a punta, igual que [FrameCapturer]: sin sesión, sin red, un disco lleno o
 * un 404 puntual -todo se traga acá (logueado) y se reintenta en la próxima pasada. Nunca lanza,
 * salvo [CancellationException], que se re-lanza siempre: tragarla dejaría corriendo una corrutina
 * que su scope ya dio por muerta.
 */
class BajadorDeFrames(
    private val almacen: AlmacenDeFrames,
    private val dao: EpisodeFrameDao,
    private val client: PocketBaseClient,
    private val deviceAuth: DeviceAuthManager,
) {
    // Evita pasadas superpuestas: quien dispara bajarPendientes() (ver ArkivRepository) lo hace
    // desde CADA emisión de los Flow que leen frames, y una ráfaga de upserts de sync puede
    // reemitirlos varias veces seguidas. Sin este guard, cada emisión abriría su propia pasada
    // -mismo archivo pendiente, otro file-token pedido de más- en vez de dejar que la que ya está
    // en curso termine y limpie la cola.
    //
    // Que una llamada se descarte no pierde trabajo: la pasada en curso escribe en `episode_frame`
    // por cada archivo que publica, Room invalida la tabla y los Flow que disparan esto reemiten
    // -con lo cual el pedido descartado vuelve enseguida, ahora sí con la pasada libre. Si la
    // pasada en curso no escribe nada (falló la red), tampoco había forma de que la descartada
    // bajara nada.
    private val bajando = AtomicBoolean(false)

    /**
     * Baja el JPEG de los [episodeIds] que estén pendientes. Se puede llamar tan seguido como haga
     * falta: si ya hay una pasada en curso, esta llamada no hace nada (ver [bajando]); si ninguno
     * de esos capítulos tiene nada pendiente, tampoco.
     */
    suspend fun bajarPendientes(episodeIds: Collection<String>) {
        if (episodeIds.isEmpty()) return
        if (!bajando.compareAndSet(false, true)) return
        try {
            try {
                bajarTodo(episodeIds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ArkivPB", "bajarPendientes falló (se reintenta): ${e.message}", e)
            }
        } finally {
            bajando.set(false)
        }
    }

    private suspend fun bajarTodo(episodeIds: Collection<String>) {
        val pendientes = dao.pendientesDeBajar(episodeIds)
        if (pendientes.isEmpty()) return
        val session = deviceAuth.session.value ?: return // sin sesión todavía: se reintenta en la próxima pasada
        // De quién son estos frames. Se relee antes de CADA escritura (ver [esDeLaMismaCuenta]):
        // un logout a mitad de pasada ya corrió `destruirTodo`, y seguir escribiendo recrearía en
        // `filesDir/frames` las escenas de la identidad anterior -exactamente lo que ese wipe existe
        // para evitar- además de reinsertar filas que después se empujarían con el accountId NUEVO.
        val cuenta = session.accountId

        // Si el POST del paso 1 falla (red caída, servidor abajo), toda la pasada se rinde acá:
        // sin file-token no hay forma de bajar NINGÚN archivo, así que no tiene sentido seguir
        // fila por fila. El catch de bajarPendientes() se encarga de tragar la excepción.
        val fileToken = client.fileToken(session.token)

        // Resiliente por fila: un 404 puntual o un JPEG corrupto en una fila no puede tumbar el
        // resto de la cola, igual que en CloudSyncManager.pushFrames.
        for (fila in pendientes) {
            val url = fila.remoteUrl ?: continue
            if (!esDeLaMismaCuenta(cuenta)) {
                Log.w("ArkivPB", "bajarPendientes: cambió la sesión a mitad de pasada, se aborta")
                return
            }
            try {
                val bytes = client.downloadFile(url, fileToken)
                publicar(fila.episodeId, fila.updatedAt, bytes, cuenta)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ArkivPB", "bajarPendientes: falló el frame de ${fila.episodeId}: ${e.message}", e)
            }
        }
    }

    /**
     * Publica los bytes de un frame y saca su fila de la cola, o no deja rastro.
     *
     * ESCRIBE Y REVIERTE, en vez de releer la fila antes de escribir: entre una relectura y la
     * escritura queda una ventana igual de mala, mientras que [EpisodeFrameDao.marcarBajado] es un
     * UPDATE condicional -el ÚNICO punto atómico disponible- que dice, después del hecho, si la
     * fila seguía siendo la que se leyó. Si tocó 0 filas es porque cambió abajo nuestro, y en
     * cualquiera de esas formas nuestros bytes están de más:
     * - corrió `DestructorDeFrames.destruir` (el capítulo pasó el 60%, o llegó el `watched` del
     *   otro aparato): el JPEG que acabamos de escribir es justo el que ese borrado eliminó, y
     *   `rutaSiExiste` -que es la fuente de verdad de qué se pinta- lo volvería a mostrar;
     * - o aterrizó una captura local más nueva: su propio archivo ya lo pisamos nosotros, así que
     *   borrar es la única forma de no dejar publicada una escena vieja bajo una fila nueva.
     * Lo que queda tras revertir (fila viva sin archivo) es el estado tolerado y documentado en
     * `ArkivRepository.observeEpisodeFrames`, y la próxima captura del capítulo lo resuelve.
     */
    private suspend fun publicar(episodeId: String, updatedAt: Long, bytes: ByteArray, cuenta: String) {
        almacen.guardar(episodeId, bytes)
        // Segunda revalidación, pegada a la escritura de la fila: el logout pudo caer entre el
        // guardar() de arriba y este UPDATE.
        if (!esDeLaMismaCuenta(cuenta) || dao.marcarBajado(episodeId, updatedAt) == 0) {
            almacen.borrar(episodeId)
        }
    }

    /** La sesión sigue siendo la misma de cuando arrancó la pasada (no hubo logout ni cambio de cuenta). */
    private fun esDeLaMismaCuenta(cuenta: String): Boolean = deviceAuth.session.value?.accountId == cuenta
}
