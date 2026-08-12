package com.arkiv.player.ui.live

import android.content.Context
import android.content.SharedPreferences
import android.telephony.TelephonyManager
import com.arkiv.player.data.db.LiveChannelCacheDao
import com.arkiv.player.data.db.LiveChannelCacheEntity
import com.arkiv.player.data.gateway.LiveApi
import com.arkiv.player.data.gateway.LiveChannel
import java.util.Locale
import java.util.TimeZone

/**
 * Categorías del portal que representan un país, por código ISO-3166 alpha-2. Los nombres son los
 * que devuelve `/v1/live/categories` **tal cual** (con tilde: "México", "Perú", "Panamá") -- el
 * cruce es por nombre y no por id porque los ids del portal son suyos y podrían cambiar; el nombre
 * es lo que se ve y lo que se mantiene estable.
 *
 * Guatemala, Nicaragua y Belice no tienen categoría propia en el portal: caen a "Centroamérica",
 * que es lo más cercano que existe. Un país sin entrada acá simplemente no suma canales a la fila
 * (ver [canalesDelPaisParaHome]): mejor la fila corta que la fila de otro país.
 */
val CATEGORIAS_POR_PAIS: Map<String, String> = mapOf(
    "CO" to "Colombia",
    "VE" to "Venezuela",
    "EC" to "Ecuador",
    "CL" to "Chile",
    "MX" to "México",
    "PE" to "Perú",
    "BO" to "Bolivia",
    "UY" to "Uruguay",
    "PY" to "Paraguay",
    "PA" to "Panamá",
    "PR" to "Puerto Rico",
    "ES" to "España",
    "CR" to "Costa Rica",
    "US" to "Estados Unidos",
    "HN" to "Honduras",
    "SV" to "El Salvador",
    "DO" to "República Dominicana",
    "GT" to "Centroamérica",
    "NI" to "Centroamérica",
    "BZ" to "Centroamérica",
)

/**
 * País del aparato a partir de tres señales, en orden de confianza. Todas son gratis y **ninguna
 * pide permisos**; devuelve el ISO alpha-2 en mayúsculas, o `null` si ninguna sirve.
 *
 * 1. [sim]: el país de la SIM. Es la más fiable donde existe, pero un Fire TV o una tablet sin
 *    módem no la tienen.
 * 2. [regionZonaHoraria]: la región de la zona horaria (ICU resuelve "America/Bogota" → "CO").
 *    Va **antes** que el idioma a propósito: en un TV box el idioma suele quedar en inglés de
 *    fábrica, pero la zona horaria se configura al enchufarlo -- si mandara el idioma, un Fire TV
 *    en Bogotá mostraría canales de Estados Unidos.
 * 3. [localeCountry]: el país de la configuración regional, último recurso.
 *
 * Se descarta lo que no sea un código de dos letras: ICU devuelve "001" (mundo) o "419"
 * (Latinoamérica) para zonas genéricas tipo "Etc/UTC", y eso no es un país.
 */
fun paisDesdeSenales(
    sim: String?,
    regionZonaHoraria: String?,
    localeCountry: String?,
): String? = listOf(sim, regionZonaHoraria, localeCountry)
    .firstOrNull { it != null && it.length == 2 && it.all { c -> c.isLetter() } }
    ?.uppercase(Locale.ROOT)

/** [paisDesdeSenales] leyendo las señales reales del aparato. */
fun paisDelAparato(context: Context): String? {
    // getSystemService devuelve null donde no hay telefonía (Fire TV), y simCountryIso viene "" con
    // el módem sin SIM: los dos casos caen solos al resto de señales.
    val sim = runCatching {
        (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
            ?.simCountryIso
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    val region = runCatching {
        android.icu.util.TimeZone.getRegion(TimeZone.getDefault().id)
    }.getOrNull()

    return paisDesdeSenales(sim, region, Locale.getDefault().country)
}

/**
 * La fila "Canales en vivo" del home: primero lo último visto (el orden que ya trae [recientes],
 * acá no se reordena), después los canales del país, y **sin repetir** -- si Caracol está en los
 * recientes, no vuelve a aparecer entre los del país. El tope [limite] es del total de la fila,
 * no de cada parte: la fila es un atajo, y la parrilla completa está a un paso, en la tarjeta
 * "Ver más canales" con la que quien pinta la fila la cierra.
 *
 * El dedup es por `code` (el identificador del portal), no por nombre: dos canales distintos
 * pueden llamarse parecido, y el mismo canal puede venir con nombres distintos según la categoría.
 */
fun filaDeCanalesDelHome(
    recientes: List<LiveChannel>,
    delPais: List<LiveChannel>,
    limite: Int = LIMITE_FILA_HOME,
): List<LiveChannel> {
    val vistos = HashSet<String>()
    // Recientes primero, ya deduplicados entre sí por si acaso: `add` devuelve false en el repetido.
    val fila = recientes.filter { vistos.add(it.code) } + delPais.filter { vistos.add(it.code) }
    return fila.take(limite)
}

/** Tope de tarjetas de la fila del home, sin contar "Ver más canales". */
const val LIMITE_FILA_HOME = 24

/**
 * Canales del país del aparato para la fila del home. Devuelve lista vacía -- nunca lanza -- si no
 * se detecta país, si ese país no tiene categoría en el portal, o si no hay ni red ni caché.
 *
 * Frescura: con caché de menos de [FRESCURA_MS] no toca la red. Es un catálogo de canales, no algo
 * que cambie en el día, y esta función corre en **cada apertura del home**.
 *
 * El id de la categoría se guarda en [prefs] junto al ISO que lo originó. Sin eso habría que pedir
 * `categorias()` al gateway solo para saber qué leer de la caché local, y el atajo del home dejaría
 * de funcionar sin red. Va en SharedPreferences y no en `SettingsStore` porque no es un ajuste del
 * usuario: es caché derivada, reconstruible pidiéndole las categorías al gateway.
 */
suspend fun canalesDelPaisParaHome(
    context: Context,
    api: LiveApi,
    cacheDao: LiveChannelCacheDao,
    prefs: SharedPreferences,
    ahoraMs: Long = System.currentTimeMillis(),
): List<LiveChannel> {
    val iso = paisDelAparato(context) ?: return emptyList()
    val nombreCategoria = CATEGORIAS_POR_PAIS[iso] ?: return emptyList()

    val idGuardado = if (prefs.getString(KEY_PAIS_ISO, null) == iso) {
        prefs.getInt(KEY_PAIS_CATEGORIA, 0).takeIf { it != 0 }
    } else {
        null
    }

    if (idGuardado != null) {
        val cacheados = cacheDao.deCategoria(idGuardado)
        val fresca = cacheados.isNotEmpty() && cacheados.all { ahoraMs - it.guardadoAt < FRESCURA_MS }
        if (fresca) return cacheados.map { LiveChannel(it.code, it.nombre, it.numero, it.logo) }
    }

    val frescos = runCatching {
        val id = idGuardado
            ?: api.categorias().firstOrNull { it.nombre == nombreCategoria }?.id
            ?: return emptyList()
        val canales = api.canales(id)
        if (canales.isNotEmpty()) {
            cacheDao.reemplazar(id, canales.map {
                LiveChannelCacheEntity(it.code, id, it.nombre, it.numero, it.logo, ahoraMs)
            })
            prefs.edit().putString(KEY_PAIS_ISO, iso).putInt(KEY_PAIS_CATEGORIA, id).apply()
        }
        canales
    }.getOrNull()

    if (frescos != null && frescos.isNotEmpty()) return frescos

    // Sin red: la caché vieja sirve igual -- un catálogo de canales desactualizado es mejor que una
    // fila a medias, y los canales que ya no existan fallarán al abrirse, como cualquier otro.
    val id = idGuardado ?: return emptyList()
    return cacheDao.deCategoria(id).map { LiveChannel(it.code, it.nombre, it.numero, it.logo) }
}

private const val KEY_PAIS_ISO = "live_pais_iso"
private const val KEY_PAIS_CATEGORIA = "live_pais_categoria_id"

/** 24 h: los canales de un país no cambian en el día. */
private const val FRESCURA_MS = 24L * 60 * 60 * 1000
