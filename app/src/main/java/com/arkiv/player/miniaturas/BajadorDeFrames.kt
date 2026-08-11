package com.arkiv.player.miniaturas

import android.util.Log
import com.arkiv.player.data.db.EpisodeFrameDao
import com.arkiv.player.pocketbase.DeviceAuthManager
import com.arkiv.player.pocketbase.PocketBaseClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Baja el JPEG de los frames que llegaron por sync desde otro dispositivo: gemela de
 * [FrameCapturer], pero del lado de la lectura. `CloudSyncManager.mergeFrame` ya dejó la fila en
 * Room con `remoteUrl` apuntando al archivo remoto (ver `EpisodeFrameDao.pendientesDeBajar`);
 * a esta clase solo le falta traer los bytes y publicarlos con [AlmacenDeFrames.guardar].
 *
 * DOS PASOS para bajar el archivo, no uno solo -y esto no es un detalle, es LA razón de ser de
 * esta clase-: el campo `img` de la colección `episode_frames` se creó con `protected = true`
 * (ver `docs/pocketbase/1786500000_created_episode_frames.js`) porque son escenas de lo que mira
 * el usuario, y PocketBase NO sirve un archivo protegido con el header `Authorization` de la
 * sesión del dispositivo. Hace falta:
 *   1. `POST /api/files/token` con la sesión, que devuelve un file-token de vida corta.
 *   2. `GET remoteUrl?token=<ese file-token>`, recién ahí vienen los bytes.
 * Si algún día las miniaturas remotas dejan de cargar (se ven solo las locales, o el respaldo de
 * TMDB en cada capítulo de otro dispositivo), ES ACÁ donde hay que mirar primero.
 *
 * El file-token se pide DE NUEVO en cada pasada (nunca se cachea entre pasadas): es de vida
 * corta, y guardar uno viejo para la próxima vuelta terminaría en 403 más seguido de lo necesario
 * en vez de simplemente pedir uno fresco.
 *
 * Best-effort de punta a punta, igual que [FrameCapturer]: sin sesión, sin red, un disco lleno o
 * un 404 puntual -todo se traga acá y se reintenta en la próxima pasada. Nunca lanza.
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
    private val bajando = AtomicBoolean(false)

    /**
     * Recorre [EpisodeFrameDao.pendientesDeBajar] y trae los bytes de cada una. Se puede llamar
     * tan seguido como haga falta: si ya hay una pasada en curso, esta llamada no hace nada (ver
     * [bajando]); si la cola está vacía, tampoco.
     */
    suspend fun bajarPendientes() {
        if (!bajando.compareAndSet(false, true)) return
        try {
            runCatching { bajarTodo() }
                .onFailure { e -> Log.w("ArkivPB", "bajarPendientes falló (se reintenta): ${e.message}") }
        } finally {
            bajando.set(false)
        }
    }

    private suspend fun bajarTodo() {
        val pendientes = dao.pendientesDeBajar()
        if (pendientes.isEmpty()) return
        val session = deviceAuth.session.value ?: return // sin sesión todavía: se reintenta en la próxima pasada

        // Si el POST del paso 1 falla (red caída, servidor abajo), toda la pasada se rinde acá:
        // sin file-token no hay forma de bajar NINGÚN archivo, así que no tiene sentido seguir
        // fila por fila. El runCatching de bajarPendientes() se encarga de tragar la excepción.
        val fileToken = client.fileToken(session.token)

        // Resiliente por fila: un 404 puntual o un JPEG corrupto en una fila no puede tumbar el
        // resto de la cola, igual que en CloudSyncManager.pushFrames.
        for (fila in pendientes) {
            val url = fila.remoteUrl ?: continue
            runCatching {
                val bytes = client.downloadFile(url, fileToken)
                almacen.guardar(fila.episodeId, bytes)
                // remoteUrl = null es lo que saca a esta fila de pendientesDeBajar(): sin esto,
                // la misma fila se re-bajaría en cada pasada para siempre.
                dao.upsert(fila.copy(remoteUrl = null))
            }
        }
    }
}
