package com.arkiv.player.data.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class LiveCategory(val id: Int, val nombre: String)

data class LiveChannel(
    val code: String,
    val nombre: String,
    val numero: Int,
    val logo: String?,
    /**
     * Si el canal viene de una categoría de adultos.
     *
     * Va en el CANAL y no solo en la categoría porque el canal viaja solo hasta el reproductor
     * —zapping, deep link, la propia lista de recientes— y ahí ya no hay categoría a mano. Con la
     * marca encima, la regla de "esto no se anota en el historial" se aplica en el punto de
     * escritura y no depende de por dónde llegó.
     *
     * Por defecto `false`: el que no sabe, no marca. Los caminos que reconstruyen un `LiveChannel`
     * sin categoría a mano (favoritos, caché de `CountryChannels`, recientes, el fallback de zapping
     * de `PlayerViewModel.loadLive`) no lo pasan y se quedan con el default.
     */
    val adulto: Boolean = false,
)

/** Tiempos en epoch **segundos**, como los manda el portal. */
data class LiveProgram(val titulo: String, val inicio: Long, val fin: Long, val sinopsis: String)

/**
 * Un CDN donde se puede pedir la señal, con SU propio `authBase`.
 *
 * Es lo que el proxy local necesita para hablarle al CDN: la excepción consciente al patrón de
 * `ref` opaco de los modelos `Gateway*` — acá la app sí necesita los datos en claro para armar
 * las cabeceras de cada segmento.
 *
 * El token de firma viaja dentro de esa url, así que van juntos: firmar con el token de un CDN
 * contra el host de otro es exactamente el par que el CDN rechaza con 401.
 */
data class CdnDeCanal(val cflHost: String, val authBase: String) {
    /** El `token=<32 hex>` que va dentro de `authBase`; es lo único que la firma necesita. */
    val token: String get() = Regex("token=([0-9A-Fa-f]{32})").find(authBase)?.groupValues?.get(1).orEmpty()
}

data class LiveSession(
    val cflHost: String,
    val authBase: String,
    val license: String,
    /** El código con el que se PIDIÓ el canal: la clave con la que se cachea e invalida su
     *  sesión, y el nombre con el que aparece en los logs. NO sirve para hablarle al CDN. */
    val channel: String,
    val expiresAt: Long,
    /**
     * Cómo se llama la señal EN EL CDN — lo que va en `/live/{...}.m3u8`.
     *
     * No siempre coincide con [channel]. Medido el 2026-08-14: `cyx-RCNHD` se sirve como
     * `cyx-2EF7E10E40C1ac19D6A9F3ED4CD2`, mientras que `cyx_9881490555304164628541864337` es
     * igual en los dos. Usar [channel] acá le pedía al CDN una señal distinta de la que
     * autoriza la licencia que se le manda, y contestaba 401 — el canal quedaba cargando para
     * siempre. Los canales que andaban eran justo aquellos donde los dos coinciden.
     *
     * Por defecto ES [channel], que es el comportamiento de siempre: es lo correcto para los
     * canales donde coinciden y para un gateway que todavía no mande el campo.
     */
    val playCode: String = channel,
    /**
     * TODOS los CDN donde se puede pedir esta señal, en el orden que los dio el portal.
     *
     * Medido el 2026-08-14: el portal devuelve tres entradas de vivo y se usaba solo la primera.
     * Ese día el CDN contestó 401 dos veces y el canal se terminó (`EndReached`) teniendo otro
     * host disponible en la misma respuesta. Con la lista, un rechazo pasa a ser "probá el
     * siguiente" en vez de un canal muerto.
     *
     * Por defecto es el primero solo — lo que había antes — para un gateway que todavía no manda
     * la lista.
     */
    val cdns: List<CdnDeCanal> = listOf(CdnDeCanal(cflHost, authBase)),
) {
    /** El `token=<32 hex>` que va dentro de `authBase`; es lo único que la firma necesita. */
    val token: String get() = Regex("token=([0-9A-Fa-f]{32})").find(authBase)?.groupValues?.get(1).orEmpty()
}

/** Un ítem del catálogo de Magis (una película/video de una sección). */
data class ItemDeCatalogo(
    val id: String,
    val titulo: String,
    val poster: String?,
    val duracionS: Int,
    /**
     * Si vino de una sección de adultos. Va en el ÍTEM y no solo en la sección porque el ítem
     * viaja solo hasta el reproductor, y ahí la regla de "esto no se anota en el historial" tiene
     * que poder aplicarse sin saber de dónde vino. Es lo mismo que se hizo con [LiveChannel].
     */
    val adulto: Boolean = false,
    /**
     * El ref con el que se le pide el stream al portal al reproducir (`MagisLive`/`MagisResolve`).
     * Es lo ÚNICO reproducible que trae el ítem: la resolución NO toma [id] (que es el contentId
     * del portal), toma este string. Es un descriptor LOCAL -`MagisRef(id, tipo, 0).encode()`,
     * ver `MagisLiveCatalog.kt`-: nadie lo firma ni lo acuña, así que tampoco vence (antes sí,
     * a las 24 h, cuando lo armaba el gateway -ver el KDoc de `MagisRef`-). La app lo sigue
     * tratando como opaco y nunca lo interpreta, pero ya no por criptografía: por contrato.
     *
     * El default en `""` es defensivo, no algo que pase hoy: el único sitio que construye un
     * [ItemDeCatalogo] (`MagisLiveCatalog`) descarta antes cualquier `contentId` en blanco, así
     * que en la práctica este campo nunca sale vacío. Si alguna vez lo estuviera, el ítem se
     * lista igual —se puede ver— pero no se reproduce; ver [reproducible].
     */
    val ref: String = "",
    /** Lo que el portal dice que es: "movie", "teleplay"… Ver [esSerie]. */
    val tipo: String = "movie",
) {
    /**
     * Si hay que pedirle los capítulos antes de reproducir, en vez de reproducirlo derecho.
     *
     * Se pregunta por [tipo] y no abriendo el [ref] a propósito: el ref es opaco para la app, y
     * que siga siéndolo es lo que deja al gateway cambiarle la forma sin publicar un APK.
     */
    val esSerie: Boolean get() = tipo == "teleplay"

    val reproducible: Boolean get() = ref.isNotBlank()
}

/** Una sección del catálogo, con sus primeros ítems (el portal los manda en la misma respuesta). */
data class SeccionDeCatalogo(
    val id: Int,
    val nombre: String,
    val adulto: Boolean,
    val items: List<ItemDeCatalogo>,
)

data class LiveSignature(val moment: Long, val sign2: String)

/**
 * Lo que [com.arkiv.player.ui.live.LiveViewModel] necesita del catálogo de vivo -- angosta a
 * propósito: `resolver`/`firmar` son de [com.arkiv.player.ui.live.LiveController] (resolución de
 * sesión y firma de segmentos), un consumidor completamente distinto con su propio ciclo de vida;
 * meterlos acá solo ataría esta interfaz a un consumidor que no la usa.
 *
 * La implementa [com.arkiv.player.data.magis.MagisLiveCatalog], que habla con el portal directo
 * (antes la implementaba el cliente del gateway). En tests, un doble liviano la implementa directo
 * (ver `FakeLiveApi` en `LiveViewModelTest.kt`) sin tocar red.
 */
interface LiveCatalogGateway {
    /**
     * @param incluirAdultos pide también la categoría 18+. El gateway la filtra por DEFECTO, así
     *   que sin esto no viene — ver `AdultsLock`. Es un candado de control remoto, no una
     *   frontera de seguridad: quien arme el pedido a mano puede ponerlo igual.
     */
    suspend fun categorias(incluirAdultos: Boolean = false): List<LiveCategory>
    suspend fun canales(categoria: Int): List<LiveChannel>
    suspend fun epg(codes: List<String>): Pair<Map<String, List<LiveProgram>>, List<String>>
}
