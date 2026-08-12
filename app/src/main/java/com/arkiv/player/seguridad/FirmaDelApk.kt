package com.arkiv.player.seguridad

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Comprueba que el APK que se está ejecutando lo firmamos nosotros.
 *
 * Es lo primero que hace una app bancaria, y sin esto el bloqueo por root no vale nada: cualquiera
 * decompila el APK, borra la comprobación, lo vuelve a firmar con SU llave y listo. La firma es lo
 * único que no puede replicar — para eso tendría que tener el keystore.
 *
 * Solo se exige en RELEASE. Las builds de depuración van firmadas con la llave de debug de Android,
 * y son las que se instalan por ADB para probar en el Fire TV: exigirlo ahí sería bloquearse a uno
 * mismo en cada prueba.
 */
object FirmaDelApk {

    /**
     * SHA-256 del certificado de release (`CN=Arkiv, O=Arkiv, L=Bogota, C=CO`), leído con
     * `apksigner verify --print-certs` sobre el APK firmado. Si alguna vez se rota el keystore
     * —cosa que no se puede, ver [[arkiv-release-keystore]]— hay que actualizarlo acá.
     */
    const val SHA256_RELEASE = "35c2ff7d172f3001a48edac842dbe46058b80f8ae7c1ce3943d1c3b75477af31"

    /** Huellas SHA-256 de los certificados con los que está firmado el APK en ejecución. */
    fun huellas(context: Context): List<String> = runCatching {
        val pm = context.packageManager
        val firmas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.let {
                if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        firmas.orEmpty().map { firma ->
            MessageDigest.getInstance("SHA-256").digest(firma.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }.getOrDefault(emptyList())

    /**
     * ¿Está firmado con la llave que corresponde?
     *
     * En debug siempre `true`: ver el KDoc de la clase. Si no se pudo leer ninguna firma también se
     * responde `true` — no hay prueba de manipulación, y dejar sin app a alguien por una lectura
     * fallida es peor que el riesgo que esto cubre.
     */
    fun esNuestra(context: Context, esDebug: Boolean): Boolean {
        if (esDebug) return true
        val huellas = huellas(context)
        if (huellas.isEmpty()) return true
        return huellas.any { it.equals(SHA256_RELEASE, ignoreCase = true) }
    }
}
