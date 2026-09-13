package com.arkiv.player.data.caracol

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.dash.offline.DashDownloader
import java.io.File

/**
 * Dónde viven los capítulos de Caracol bajados al dispositivo, y cómo se leen.
 *
 * Un capítulo de Caracol no se puede guardar como archivo: su video es DASH, o sea miles de
 * segmentos, y además viene CIFRADO (CENC/Widevine). Lo que se guarda son esos segmentos tal cual
 * los sirve el CDN, dentro del caché de media3. Nada se descifra acá ni en ningún otro lado de la
 * app: la llave nunca sale del CDM del aparato, y por eso reproducir sigue necesitando pedirle al
 * servidor de Caracol una licencia de streaming —unos pocos KB— en el momento de darle play.
 * Licencias persistentes no da: medido, responde 500 con `X-DRM-Error` (ver
 * `SondaDeCaracolOffline`, en `src/debug`).
 *
 * UNA sola instancia por proceso: `SimpleCache` se niega a abrir dos veces la misma carpeta, así
 * que esto vive en `AppGraph` y tanto la descarga como el reproductor piden el mismo objeto.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class AlmacenDeCaracol(private val context: Context, private val carpeta: File) {

    /**
     * Sin desalojo automático ([NoOpCacheEvictor]): esto NO es un caché que se llena solo y se
     * limpia solo, es lo que la persona pidió bajar. Un evictor por tamaño borraría capítulos que
     * alguien guardó a propósito, y la fila de `downloads` seguiría diciendo "Listo". Quien borra
     * es `LocalDownloadManager.remove`, por pedido explícito.
     */
    val cache: Cache by lazy {
        SimpleCache(carpeta.apply { mkdirs() }, NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
    }

    /** Lo que ocupan en disco todas las descargas de Caracol juntas. */
    fun bytesEnDisco(): Long = runCatching { cache.cacheSpace }.getOrDefault(0L)

    /**
     * La fuente de datos para BAJAR: caché con la red detrás, y escribiendo lo que traiga.
     *
     * [headers] lleva la cookie `playback_token`. No la necesita el CDN de video —sirve los
     * segmentos a quien los pida— pero sí el manifiesto, y mandarla de más no cuesta nada.
     */
    fun fabricaParaBajar(headers: Map<String, String>): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(fabricaHttp(headers))

    /**
     * La fuente de datos para REPRODUCIR: primero el disco, y la red solo para lo que falte.
     *
     * No escribe ([setCacheWriteDataSinkFactory] en null) a propósito. Si escribiera, ver un
     * capítulo por streaming iría dejando bytes en el mismo caché sin que ninguna fila de
     * `downloads` los reclame: disco que crece y que nada sabe borrar. Lo que entra acá entra por
     * una descarga pedida.
     */
    fun fabricaParaVer(headers: Map<String, String>): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(fabricaHttp(headers))
            .setCacheWriteDataSinkFactory(null)

    /** El descargador de un capítulo. El mismo objeto sabe bajar y sabe BORRAR lo que bajó. */
    fun descargador(item: MediaItem, headers: Map<String, String>): DashDownloader =
        DashDownloader(item, fabricaParaBajar(headers))

    /**
     * Los mismos tres headers que usa `DituCliente`. Sin ellos el CDN de Caracol responde 403 al
     * manifiesto, y ese fallo se lee como "el video no existe".
     */
    fun fabricaHttp(headers: Map<String, String>): HttpDataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setUserAgent("okhttp/4.12.0")
            .setDefaultRequestProperties(mapOf("restful" to "yes") + headers)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
}
