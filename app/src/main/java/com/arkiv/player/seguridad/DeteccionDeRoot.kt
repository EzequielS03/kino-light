package com.arkiv.player.seguridad

/**
 * Lo que se pudo observar del dispositivo. Va aparte de la decisión a propósito: recoger esto toca
 * el sistema de archivos y el PackageManager, y así [DeteccionDeRoot.motivos] queda pura y testeable
 * sin levantar Android.
 */
data class SenalesDeRoot(
    /** Rutas de binarios `su` que existen. */
    val binariosSu: List<String> = emptyList(),
    /** Paquetes de gestores de root instalados. */
    val paquetesDeRoot: List<String> = emptyList(),
    /** Rutas propias de Magisk que existen. */
    val rastrosDeMagisk: List<String> = emptyList(),
    /** `Build.TAGS`. */
    val tags: String = "",
    /** `Build.TYPE`. */
    val tipo: String = "",
    /** Líneas sospechosas halladas en la tabla de montajes del propio proceso. */
    val montajesSospechosos: List<String> = emptyList(),
    /**
     * Si dos hilos del MISMO proceso ven tablas de montaje distintas. Un proceso normal no puede:
     * el namespace es del proceso. Ver [DeteccionDeRoot].
     */
    val montajesInconsistentes: Boolean = false,
)

/**
 * Decide si el dispositivo está rooteado.
 *
 * **Lo que esto NO es**: no impide instalar la app. Android no ofrece ningún mecanismo para que un
 * APK sideloaded se niegue a instalarse según el estado del aparato — el instalador no ejecuta
 * código nuestro. Lo único posible es negarse a FUNCIONAR, que es lo que hace [MainActivity].
 *
 * **Y no es infalible**: Magisk con Shamiko/PIF existe justamente para esconderse. Contra ese setup,
 * las señales "de superficie" (binarios `su`, paquetes, tags) fallan: Shamiko engancha el arranque
 * del proceso ANTES de que la app mire, desmonta lo que delata y oculta sus paquetes del
 * PackageManager. Por eso están las dos señales de montajes, que es lo único que la práctica actual
 * reporta como resistente:
 *
 * - **Rastros en la tabla de montajes.** Aunque se limpie lo evidente, el modo systemless deja
 *   entradas que apuntan a `/data/adb` o superponen `/system`.
 * - **Inconsistencia entre hilos.** El namespace de montaje es del PROCESO: dos hilos del mismo
 *   proceso NO pueden ver tablas distintas. Cuando se manipula el namespace en el arranque, los
 *   hilos creados antes de ese momento conservan la vista vieja, y la diferencia delata la
 *   manipulación sin importar cuánto se esconda lo demás.
 *
 * **Play Integrity no es opción acá**, aunque sea lo que Google recomienda: exige Google Play
 * Services, y el Fire TV Stick corre Fire OS, que no los tiene. Y el APK se reparte por fuera de
 * Play, así que el veredicto de la app tampoco aplicaría.
 *
 * Las señales están elegidas para no dar falso positivo en un aparato de fábrica. Medido en el Fire
 * TV Stick (AFTKM) el 2026-08-12: `tags=amz-p,release-keys`, `tipo=user`, sin binarios `su`. Ojo con
 * `tags`: trae más cosas que "release-keys", así que hay que buscar "test-keys" dentro, no comparar
 * la cadena entera.
 */
object DeteccionDeRoot {

    /** Dónde suele quedar el binario `su`. */
    val RUTAS_SU = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/system/sbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/vendor/bin/su",
        "/data/local/su",
        "/data/local/bin/su",
        "/data/local/xbin/su",
    )

    /** Gestores de root y apps que solo tienen sentido con root. */
    val PAQUETES_DE_ROOT = listOf(
        "com.topjohnwu.magisk",
        "io.github.huskydg.magisk",
        "com.noshufou.android.su",
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "me.weishu.kernelsu",
        "com.yellowes.su",
    )

    /** Rastros que deja Magisk aunque no esté su app instalada. */
    val RASTROS_DE_MAGISK = listOf(
        "/sbin/.magisk",
        "/data/adb/magisk",
        "/data/adb/modules",
        "/cache/.disable_magisk",
    )

    /** Tipos de compilación que no son de un aparato de fábrica. */
    private val TIPOS_ABIERTOS = setOf("userdebug", "eng")

    /**
     * Qué delata a un montaje. `/data/adb` es donde vive Magisk, y un `overlay`/`tmpfs` montado
     * sobre `/system` o `/vendor` es exactamente la técnica systemless: el sistema real no se toca,
     * se le superpone otra cosa.
     */
    fun montajeEsSospechoso(linea: String): Boolean {
        val l = linea.lowercase()
        if ("magisk" in l || "/data/adb" in l || "kernelsu" in l) return true
        val superpone = " overlay " in l || " tmpfs " in l
        return superpone && (" /system" in l || " /vendor" in l)
    }

    /**
     * Por qué se considera rooteado este aparato. Vacío = limpio.
     *
     * Se devuelven los motivos y no un booleano para que la pantalla de bloqueo pueda decir qué se
     * encontró: un aviso que no explica nada es indistinguible de un bug.
     */
    fun motivos(senales: SenalesDeRoot): List<String> = buildList {
        senales.binariosSu.forEach { add("binario su en $it") }
        senales.paquetesDeRoot.forEach { add("app de root instalada: $it") }
        senales.rastrosDeMagisk.forEach { add("rastro de Magisk en $it") }
        if (senales.tags.contains("test-keys", ignoreCase = true)) {
            add("compilación firmada con test-keys")
        }
        if (senales.tipo.lowercase() in TIPOS_ABIERTOS) {
            add("compilación de desarrollo (${senales.tipo})")
        }
        senales.montajesSospechosos.forEach { add("montaje sospechoso: $it") }
        if (senales.montajesInconsistentes) {
            add("dos hilos del proceso ven tablas de montaje distintas")
        }
    }

    fun hayRoot(senales: SenalesDeRoot): Boolean = motivos(senales).isNotEmpty()
}
