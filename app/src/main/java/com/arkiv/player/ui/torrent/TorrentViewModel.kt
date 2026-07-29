package com.arkiv.player.ui.torrent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.catalog.BROWSER_UA
import com.arkiv.player.torrent.TorrentEngine
import com.arkiv.player.torrent.TorrentMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class TorrentViewModel(
    private val engine: TorrentEngine,
    private val repo: ArkivRepository,
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Resolving : State
        data class Added(val itemId: String, val title: String) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun reset() {
        _state.value = State.Idle
    }

    /** Acepta un magnet, o una URL http(s) que apunte a un .torrent. */
    fun play(input: String) {
        val s = input.trim()
        when {
            s.startsWith("magnet:") -> resolveAndAdd { engine.resolveMagnet(s) }
            s.startsWith("http://") || s.startsWith("https://") -> resolveAndAdd { downloadAndResolve(s) }
            else -> _state.value = State.Error("Pegá un magnet, una URL .torrent, o elegí un archivo")
        }
    }

    fun playTorrentBytes(data: ByteArray) {
        resolveAndAdd { engine.resolveTorrent(data) }
    }

    /**
     * Baja un .torrent desde una URL y lo resuelve. Robustecido (Tier 4 #42): UA de navegador (pasa el
     * "Bot Fight Mode" de Cloudflare de trackers tipo Wolfmax/DivxTotal) y manejo manual de redirects,
     * porque el link suele redirigir a un magnet (OkHttp no puede seguir un Location `magnet:`) o a otra
     * URL de descarga; sin esto se alimentaba el HTML de redirección a resolveTorrent y fallaba opaco.
     */
    private suspend fun downloadAndResolve(url: String, hop: Int = 0): TorrentMeta? = withContext(Dispatchers.IO) {
        if (hop > 3) return@withContext null
        val client = OkHttpClient.Builder().followRedirects(false).build()
        val req = Request.Builder().url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/x-bittorrent,*/*")
            .build()
        val resp = runCatching { client.newCall(req).execute() }.getOrNull() ?: return@withContext null
        val loc = resp.header("Location")?.trim()
        // Redirección a magnet: OkHttp no puede seguir un esquema `magnet:`, se resuelve aparte.
        if (loc != null && loc.startsWith("magnet:")) { resp.close(); return@withContext engine.resolveMagnet(loc) }
        // Redirección http(s): seguir UN salto (no alimentar el cuerpo de redirección a resolveTorrent).
        if (resp.isRedirect && loc != null && loc.startsWith("http")) { resp.close(); return@withContext downloadAndResolve(loc, hop + 1) }
        val bytes = resp.use { it.body?.bytes() } ?: return@withContext null
        engine.resolveTorrent(bytes)
    }

    private fun resolveAndAdd(resolve: suspend () -> TorrentMeta?) {
        _state.value = State.Resolving
        viewModelScope.launch {
            val meta = runCatching { resolve() }.getOrNull()
            if (meta == null) {
                _state.value = State.Error("No se pudo leer el torrent (sin seeds o metadata no disponible)")
                return@launch
            }
            val videos = engine.videoFiles(meta).ifEmpty {
                engine.pickVideo(meta)?.let { listOf(it) } ?: emptyList()
            }
            if (videos.isEmpty()) {
                _state.value = State.Error("El torrent no tiene archivos reproducibles")
                return@launch
            }
            val itemId = runCatching {
                repo.addTorrent(meta.name, meta.infoHashHex, meta.infoBytes, videos)
            }.getOrNull()
            if (itemId == null) {
                _state.value = State.Error("No se pudo agregar el torrent a la biblioteca")
            } else {
                _state.value = State.Added(itemId, meta.name)
            }
        }
    }
}
