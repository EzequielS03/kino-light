package com.arkiv.player.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Checks that the running APK was signed by us.
 *
 * It's the first thing a banking app does, and without this the root block is worthless: anyone
 * can decompile the APK, strip the check, re-sign it with THEIR key and be done. The signature is
 * the one thing they can't replicate -- that would require the keystore itself.
 *
 * Only enforced in RELEASE. Debug builds are signed with Android's debug key, and those are the
 * ones installed via ADB to test on the Fire TV: enforcing it there would lock yourself out of
 * every test run.
 */
object ApkSignature {

    /**
     * SHA-256 of the release certificate (`CN=Arkiv, O=Arkiv, L=Bogota, C=CO`), read with
     * `apksigner verify --print-certs` over the signed APK. If the keystore is ever rotated
     * -- which it can't, see [[arkiv-release-keystore]] -- update it here.
     */
    const val SHA256_RELEASE = "35c2ff7d172f3001a48edac842dbe46058b80f8ae7c1ce3943d1c3b75477af31"

    /** SHA-256 fingerprints of the certificate(s) the running APK is signed with. */
    fun fingerprints(context: Context): List<String> = runCatching {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.let {
                if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }.getOrDefault(emptyList())

    /**
     * Is it signed with the key it should be?
     *
     * Always `true` in debug: see the class KDoc. Also `true` if no signature could be read at
     * all -- there's no proof of tampering, and locking someone out over a failed read is worse
     * than the risk this guards against.
     */
    fun isOurs(context: Context, isDebug: Boolean): Boolean {
        if (isDebug) return true
        val found = fingerprints(context)
        if (found.isEmpty()) return true
        return found.any { it.equals(SHA256_RELEASE, ignoreCase = true) }
    }
}
